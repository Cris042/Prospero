package br.prospero.fo.ordens.actor;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.ordens.dominio.BaseTunel;
import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.FonteUltimoPreco;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.PapelOco;
import br.prospero.fo.ordens.dominio.ParametrosAtivo;
import br.prospero.fo.ordens.dominio.RepositorioOrdens;
import br.prospero.fo.ordens.dominio.ResultadoRegistro;
import br.prospero.fo.ordens.dominio.TipoOrdem;
import br.prospero.fo.ordens.dominio.Validade;
import br.prospero.fo.ordens.dto.OcoRequestDTO;
import br.prospero.fo.ordens.dto.OrdemRequestDTO;
import br.prospero.fo.ordens.helper.AssinaturaEnvio;
import br.prospero.fo.ordens.helper.EntradaOrdem;
import br.prospero.fo.ordens.helper.PoliticaOrdem;
import br.prospero.fo.ordens.helper.SessaoNegociacao;
import br.prospero.fo.ordens.helper.ValidacaoPreco;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Registra ordens com idempotência por (conta, chave). Ordem das verificações (ADR-011): estrutura, matriz, lote,
 * tick e coerências; depois a chave; por fim o túnel, só para envios novos. RECEBIDA não implica reserva ou execução.
 */
// @Startup: percentual inválido impede a subida em vez de falhar no primeiro envio.
@Startup
@ApplicationScoped
public class EnviarOrdemActor {
    private static final Logger LOG = Logger.getLogger(EnviarOrdemActor.class);
    private final RepositorioOrdens repositorio;
    private final Clock relogio;
    private final MeterRegistry metricas;
    private final FonteUltimoPreco fonteUltimoPreco;
    private final BigDecimal percentualTunel;

    @Inject
    public EnviarOrdemActor(RepositorioOrdens repositorio, Clock relogio, MeterRegistry metricas,
                            FonteUltimoPreco fonteUltimoPreco,
                            @ConfigProperty(name = "prospero.ordens.tunel-percentual", defaultValue = "10")
                            BigDecimal percentualTunel) {
        if (percentualTunel.signum() <= 0 || percentualTunel.compareTo(new BigDecimal("50")) > 0) {
            throw new IllegalStateException("prospero.ordens.tunel-percentual deve estar em (0, 50].");
        }
        this.repositorio = repositorio;
        this.relogio = relogio;
        this.metricas = metricas;
        this.fonteUltimoPreco = fonteUltimoPreco;
        this.percentualTunel = percentualTunel;
    }

    /** Resultado do envio: ordens e se a resposta é repetição de um envio anterior. */
    public record Envio(List<Ordem> ordens, boolean repetido) {
    }

    public Envio enviar(String conta, String chave, OrdemRequestDTO pedido) {
        try {
            return registrarSimples(conta, chave, pedido);
        } catch (RegraNegocioException recusa) {
            contarRecusa(pedido.tipo().name(), pedido.validade(), recusa);
            throw recusa;
        }
    }

    public Envio enviarOco(String conta, String chave, OcoRequestDTO pedido) {
        try {
            return registrarOco(conta, chave, pedido);
        } catch (RegraNegocioException recusa) {
            contarRecusa("OCO", pedido.validade(), recusa);
            throw recusa;
        }
    }

    private Envio registrarSimples(String conta, String chave, OrdemRequestDTO pedido) {
        exigirConta(conta);
        ParametrosAtivo ativo = exigirAtivo(pedido.ticker());
        long quantidade = EntradaOrdem.quantidade(pedido.quantidade());
        BigDecimal limite = EntradaOrdem.preco(pedido.precoLimite());
        BigDecimal gatilho = EntradaOrdem.preco(pedido.precoGatilho());
        PoliticaOrdem.validar(pedido.tipo(), pedido.validade(), limite, gatilho);
        ValidacaoPreco.lote(quantidade, ativo);
        ValidacaoPreco.tick(limite, ativo);
        ValidacaoPreco.tick(gatilho, ativo);
        if (pedido.tipo() == TipoOrdem.STOP_LIMIT) {
            ValidacaoPreco.stopLimit(pedido.lado(), limite, gatilho);
        }
        Instant agora = relogio.instant().truncatedTo(ChronoUnit.MICROS);
        String hash = AssinaturaEnvio.hash("ORDEM", pedido.ticker(), pedido.lado(), pedido.tipo(), pedido.validade(),
                quantidade, limite, gatilho);
        Optional<Envio> repetido = repeticao(conta, chave, hash, agora, pedido.tipo().name(), pedido.validade());
        if (repetido.isPresent()) {
            return repetido.get();
        }
        // Market não tem preço na entrada: a proteção do túnel ocorre no matching (RF05).
        BaseTunel base = pedido.tipo() == TipoOrdem.MARKET ? null : baseTunel(ativo, agora);
        if (base != null) {
            ValidacaoPreco.tunel(limite, base, ativo.tick(), percentualTunel);
            ValidacaoPreco.tunel(gatilho, base, ativo.tick(), percentualTunel);
        }
        UUID id = UUID.randomUUID();
        Ordem ordem = new Ordem(id, conta, pedido.ticker(), pedido.lado(), pedido.tipo(), pedido.validade(), quantidade,
                limite, gatilho, null, null, id, EstadoOrdem.RECEBIDA, SessaoNegociacao.data(agora), agora, base);
        return registrar(conta, chave, hash, agora, List.of(ordem), pedido.tipo().name(), pedido.validade());
    }

    private Envio registrarOco(String conta, String chave, OcoRequestDTO pedido) {
        exigirConta(conta);
        ParametrosAtivo ativo = exigirAtivo(pedido.ticker());
        if (pedido.validade() != Validade.DIA) {
            throw new RegraNegocioException(Categoria.REGRA_VIOLADA, "COMBINACAO_INVALIDA", "OCO aceita somente validade DIA.");
        }
        long quantidade = EntradaOrdem.quantidade(pedido.quantidade());
        BigDecimal alvo = EntradaOrdem.preco(pedido.takeProfit().precoLimite());
        TipoOrdem tipoStop = pedido.stopLoss().tipo();
        BigDecimal gatilho = EntradaOrdem.preco(pedido.stopLoss().precoGatilho());
        BigDecimal limiteStop = EntradaOrdem.preco(pedido.stopLoss().precoLimite());
        PoliticaOrdem.validarOco(pedido.lado(), tipoStop, alvo, gatilho);
        PoliticaOrdem.validar(tipoStop, Validade.DIA, limiteStop, gatilho);
        ValidacaoPreco.lote(quantidade, ativo);
        for (BigDecimal preco : new BigDecimal[]{alvo, gatilho, limiteStop}) {
            ValidacaoPreco.tick(preco, ativo);
        }
        if (tipoStop == TipoOrdem.STOP_LIMIT) {
            ValidacaoPreco.stopLimit(pedido.lado(), limiteStop, gatilho);
        }
        Instant agora = relogio.instant().truncatedTo(ChronoUnit.MICROS);
        String hash = AssinaturaEnvio.hash("OCO", pedido.ticker(), pedido.lado(), quantidade, alvo, tipoStop, gatilho,
                limiteStop);
        Optional<Envio> repetido = repeticao(conta, chave, hash, agora, "OCO", Validade.DIA);
        if (repetido.isPresent()) {
            return repetido.get();
        }
        BaseTunel base = baseTunel(ativo, agora);
        for (BigDecimal preco : new BigDecimal[]{alvo, gatilho, limiteStop}) {
            ValidacaoPreco.tunel(preco, base, ativo.tick(), percentualTunel);
        }
        LocalDate sessao = SessaoNegociacao.data(agora);
        UUID grupo = UUID.randomUUID();
        Ordem takeProfit = new Ordem(UUID.randomUUID(), conta, pedido.ticker(), pedido.lado(), TipoOrdem.LIMIT,
                Validade.DIA, quantidade, alvo, null, grupo, PapelOco.TAKE_PROFIT, grupo, EstadoOrdem.RECEBIDA, sessao,
                agora, base);
        Ordem stopLoss = new Ordem(UUID.randomUUID(), conta, pedido.ticker(), pedido.lado(), tipoStop,
                Validade.DIA, quantidade, limiteStop, gatilho, grupo, PapelOco.STOP_LOSS, grupo, EstadoOrdem.RECEBIDA,
                sessao, agora, base);
        return registrar(conta, chave, hash, agora, List.of(takeProfit, stopLoss), "OCO", Validade.DIA);
    }

    private BaseTunel baseTunel(ParametrosAtivo ativo, Instant agora) {
        return ValidacaoPreco.base(fonteUltimoPreco.ultimoNegocio(ativo.ticker(), SessaoNegociacao.data(agora)), ativo);
    }

    /** Repetição de chave válida devolve a resposta original antes de qualquer regra dependente do mercado. */
    private Optional<Envio> repeticao(String conta, String chave, String hash, Instant agora, String tipo,
                                      Validade validade) {
        Optional<Envio> envio = repositorio.repeticao(conta, chave, hash, agora).map(this::envio);
        envio.ifPresent(ignorado -> metricas.counter("prospero.ordens.recebidas", "tipo", tipo,
                "validade", validade.name(), "resultado", "REPETIDA").increment());
        return envio;
    }

    private Envio registrar(String conta, String chave, String hash, Instant agora, List<Ordem> ordens,
                            String tipo, Validade validade) {
        Envio envio = envio(repositorio.registrar(conta, chave, hash, agora, ordens));
        metricas.counter("prospero.ordens.recebidas", "tipo", tipo, "validade", validade.name(),
                "resultado", envio.repetido() ? "REPETIDA" : "NOVA").increment();
        if (!envio.repetido()) {
            LOG.infof("Ordem registrada: envio=%s conta=%s pernas=%d", envio.ordens().getFirst().grupoEnvio(), conta,
                    envio.ordens().size());
        }
        return envio;
    }

    private Envio envio(ResultadoRegistro resultado) {
        return switch (resultado) {
            case ResultadoRegistro.Novo novo -> new Envio(novo.ordens(), false);
            case ResultadoRegistro.Repetido repetido -> new Envio(repetido.ordens(), true);
            case ResultadoRegistro.Conflito ignorado -> throw new RegraNegocioException(Categoria.CONFLITO,
                    "IDEMPOTENCIA_CONFLITO", "Chave de idempotência já usada com outro pedido.");
        };
    }

    /** Recusas também são observáveis; o código é enumerado e estável, sem dados do pedido. */
    private void contarRecusa(String tipo, Validade validade, RegraNegocioException recusa) {
        metricas.counter("prospero.ordens.recebidas", "tipo", tipo, "validade", validade.name(),
                "resultado", recusa.codigo()).increment();
    }

    private void exigirConta(String conta) {
        if (conta == null || !repositorio.contaExiste(conta)) {
            throw new RegraNegocioException(Categoria.PROIBIDO, "CONTA_DESCONHECIDA", "Token sem conta cadastrada.");
        }
    }

    private ParametrosAtivo exigirAtivo(String ticker) {
        return repositorio.parametros(ticker).orElseThrow(() ->
                new RegraNegocioException(Categoria.REGRA_VIOLADA, "ATIVO_DESCONHECIDO", "Ativo não cadastrado."));
    }
}
