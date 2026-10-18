package br.prospero.fo.ordens.actor;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.PapelOco;
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
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Registra ordens com idempotência por (conta, chave). RECEBIDA não implica validação, reserva ou execução. */
@ApplicationScoped
public class EnviarOrdemActor {
    private static final Logger LOG = Logger.getLogger(EnviarOrdemActor.class);
    private final RepositorioOrdens repositorio;
    private final Clock relogio;
    private final MeterRegistry metricas;

    @Inject
    public EnviarOrdemActor(RepositorioOrdens repositorio, Clock relogio, MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    /** Resultado do envio: ordens e se a resposta é repetição de um envio anterior. */
    public record Envio(List<Ordem> ordens, boolean repetido) {
    }

    public Envio enviar(String conta, String chave, OrdemRequestDTO pedido) {
        exigirConta(conta);
        exigirAtivo(pedido.ticker());
        long quantidade = EntradaOrdem.quantidade(pedido.quantidade());
        BigDecimal limite = EntradaOrdem.preco(pedido.precoLimite());
        BigDecimal gatilho = EntradaOrdem.preco(pedido.precoGatilho());
        PoliticaOrdem.validar(pedido.tipo(), pedido.validade(), limite, gatilho);
        Instant agora = relogio.instant().truncatedTo(ChronoUnit.MICROS);
        UUID id = UUID.randomUUID();
        Ordem ordem = new Ordem(id, conta, pedido.ticker(), pedido.lado(), pedido.tipo(), pedido.validade(), quantidade,
                limite, gatilho, null, null, id, EstadoOrdem.RECEBIDA, SessaoNegociacao.data(agora), agora);
        String hash = AssinaturaEnvio.hash("ORDEM", pedido.ticker(), pedido.lado(), pedido.tipo(), pedido.validade(),
                quantidade, limite, gatilho);
        return registrar(conta, chave, hash, agora, List.of(ordem), pedido.tipo().name(), pedido.validade());
    }

    public Envio enviarOco(String conta, String chave, OcoRequestDTO pedido) {
        exigirConta(conta);
        exigirAtivo(pedido.ticker());
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
        Instant agora = relogio.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDate sessao = SessaoNegociacao.data(agora);
        UUID grupo = UUID.randomUUID();
        Ordem takeProfit = new Ordem(UUID.randomUUID(), conta, pedido.ticker(), pedido.lado(), TipoOrdem.LIMIT,
                Validade.DIA, quantidade, alvo, null, grupo, PapelOco.TAKE_PROFIT, grupo, EstadoOrdem.RECEBIDA, sessao, agora);
        Ordem stopLoss = new Ordem(UUID.randomUUID(), conta, pedido.ticker(), pedido.lado(), tipoStop,
                Validade.DIA, quantidade, limiteStop, gatilho, grupo, PapelOco.STOP_LOSS, grupo, EstadoOrdem.RECEBIDA, sessao, agora);
        String hash = AssinaturaEnvio.hash("OCO", pedido.ticker(), pedido.lado(), quantidade, alvo, tipoStop, gatilho,
                limiteStop);
        return registrar(conta, chave, hash, agora, List.of(takeProfit, stopLoss), "OCO", Validade.DIA);
    }

    private Envio registrar(String conta, String chave, String hash, Instant agora, List<Ordem> ordens,
                            String tipo, Validade validade) {
        ResultadoRegistro resultado = repositorio.registrar(conta, chave, hash, agora, ordens);
        Envio envio = switch (resultado) {
            case ResultadoRegistro.Novo novo -> new Envio(novo.ordens(), false);
            case ResultadoRegistro.Repetido repetido -> new Envio(repetido.ordens(), true);
            case ResultadoRegistro.Conflito ignorado -> throw new RegraNegocioException(Categoria.CONFLITO,
                    "IDEMPOTENCIA_CONFLITO", "Chave de idempotência já usada com outro pedido.");
        };
        metricas.counter("prospero.ordens.recebidas", "tipo", tipo, "validade", validade.name(),
                "resultado", envio.repetido() ? "REPETIDA" : "NOVA").increment();
        LOG.infof("Ordem registrada: id=%s conta=%s repetida=%s", envio.ordens().getFirst().grupoEnvio(), conta,
                envio.repetido());
        return envio;
    }

    private void exigirConta(String conta) {
        if (conta == null || !repositorio.contaExiste(conta)) {
            throw new RegraNegocioException(Categoria.PROIBIDO, "CONTA_DESCONHECIDA", "Token sem conta cadastrada.");
        }
    }

    private void exigirAtivo(String ticker) {
        if (!repositorio.ativoExiste(ticker)) {
            throw new RegraNegocioException(Categoria.REGRA_VIOLADA, "ATIVO_DESCONHECIDO", "Ativo não cadastrado.");
        }
    }
}
