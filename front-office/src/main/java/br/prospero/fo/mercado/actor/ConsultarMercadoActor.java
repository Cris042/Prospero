package br.prospero.fo.mercado.actor;

import br.prospero.fo.mercado.dominio.Ativo;
import br.prospero.fo.mercado.dominio.Indicadores;
import br.prospero.fo.mercado.dominio.LadoAgregado;
import br.prospero.fo.mercado.dominio.Lado;
import br.prospero.fo.mercado.dominio.RegraMercadoException;
import br.prospero.fo.mercado.dominio.RepositorioMercado;
import br.prospero.fo.mercado.dominio.SnapshotMercado;
import br.prospero.fo.mercado.dto.AtivoResponseDTO;
import br.prospero.fo.mercado.dto.LadoResponseDTO;
import br.prospero.fo.mercado.dto.LivroResponseDTO;
import br.prospero.fo.mercado.dto.NivelResponseDTO;
import br.prospero.fo.mercado.helper.AgregadorLivro;
import br.prospero.fo.mercado.helper.CalculadoraIndicadores;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static br.prospero.fo.mercado.helper.ConversorDecimal.texto;

@ApplicationScoped
public class ConsultarMercadoActor {
    public static final String ORIGEM = "PREPARACAO_SINTETICA";
    private static final ZoneId SESSAO = ZoneId.of("America/Sao_Paulo");
    private final RepositorioMercado repositorio;
    private final Duration idadeMaxima;

    @Inject
    public ConsultarMercadoActor(RepositorioMercado repositorio,
                                 @ConfigProperty(name = "prospero.mercado.idade-maxima", defaultValue = "PT24H") Duration idadeMaxima) {
        this.repositorio = repositorio;
        this.idadeMaxima = idadeMaxima;
    }

    public List<AtivoResponseDTO> listar(UUID namespace) {
        Map<String, SnapshotMercado> snapshots = repositorio.snapshots(namespace).stream()
                .collect(Collectors.toMap(SnapshotMercado::ticker, Function.identity()));
        Instant agora = Instant.now();
        return repositorio.listarAtivos().stream()
                .map(ativo -> resumo(ativo, snapshots.get(ativo.ticker()), agora)).toList();
    }

    public LivroResponseDTO livro(UUID namespace, String ticker) {
        Ativo ativo = repositorio.buscarAtivo(ticker)
                .orElseThrow(() -> new RegraMercadoException("ATIVO_DESCONHECIDO", "Ativo não cadastrado."));
        SnapshotMercado snapshot = repositorio.snapshot(namespace, ativo.ticker()).orElse(null);
        Indicadores indicadores = CalculadoraIndicadores.calcular(ativo, snapshot);
        if (snapshot == null) {
            return new LivroResponseDTO(ticker, namespace, indicadores.estadoLivro().name(), null, null, null, null,
                    null, false, null);
        }
        return new LivroResponseDTO(ticker, namespace, indicadores.estadoLivro().name(),
                lado(AgregadorLivro.agregar(snapshot.ofertas(), Lado.COMPRA)),
                lado(AgregadorLivro.agregar(snapshot.ofertas(), Lado.VENDA)),
                snapshot.revisao(), snapshot.sessao(), snapshot.preparadoEm(), defasado(snapshot, Instant.now()), ORIGEM);
    }

    private AtivoResponseDTO resumo(Ativo ativo, SnapshotMercado snapshot, Instant agora) {
        Indicadores i = CalculadoraIndicadores.calcular(ativo, snapshot);
        return new AtivoResponseDTO(ativo.ticker(), ativo.nome(), texto(ativo.precoReferencia()), texto(ativo.tick()),
                ativo.lote(), texto(i.ultimoPreco()), texto(i.variacao()), texto(i.variacaoPercentual()),
                texto(i.melhorCompra()), texto(i.melhorVenda()), texto(i.spread()), i.volumeQuantidade(),
                texto(i.volumeFinanceiro()), i.estadoLivro().name(),
                snapshot == null ? null : snapshot.sessao(), snapshot == null ? null : snapshot.revisao(),
                snapshot == null ? null : snapshot.preparadoEm(), snapshot != null && defasado(snapshot, agora),
                snapshot == null ? null : ORIGEM);
    }

    private boolean defasado(SnapshotMercado snapshot, Instant agora) {
        return snapshot.preparadoEm().plus(idadeMaxima).isBefore(agora)
                || snapshot.sessao().isBefore(LocalDate.ofInstant(agora, SESSAO));
    }

    private static LadoResponseDTO lado(LadoAgregado lado) {
        return new LadoResponseDTO(lado.niveis().stream()
                .map(n -> new NivelResponseDTO(texto(n.preco()), n.quantidade(), n.ofertas(), n.quantidadeAcumulada()))
                .toList(), lado.niveisTotal(), lado.quantidadeTotal());
    }
}
