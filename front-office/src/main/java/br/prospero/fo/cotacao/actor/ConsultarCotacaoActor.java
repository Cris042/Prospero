package br.prospero.fo.cotacao.actor;

import br.prospero.fo.cotacao.dominio.CacheCotacao;
import br.prospero.fo.cotacao.dominio.CotacaoEmCache;
import br.prospero.fo.cotacao.dominio.LadoCotado;
import br.prospero.fo.cotacao.dominio.LivroCotado;
import br.prospero.fo.cotacao.dominio.RepositorioCotacao;
import br.prospero.fo.cotacao.dominio.SituacaoCotacao;
import br.prospero.fo.mercado.dominio.Ativo;
import br.prospero.fo.mercado.dominio.RegraMercadoException;
import br.prospero.fo.mercado.dominio.RepositorioMercado;
import br.prospero.fo.mercado.dto.AtivoResponseDTO;
import br.prospero.fo.mercado.dto.LadoResponseDTO;
import br.prospero.fo.mercado.dto.LeituraMercado;
import br.prospero.fo.mercado.dto.LivroResponseDTO;
import br.prospero.fo.mercado.dto.NivelResponseDTO;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static br.prospero.fo.mercado.helper.ConversorDecimal.texto;

/** Converte apenas indicadores e profundidade agregada; nenhuma identidade de oferta alcança a API. */
@ApplicationScoped
public class ConsultarCotacaoActor {
    public static final String ORIGEM = "MATCHING";
    private static final UUID PRINCIPAL = new UUID(0, 0);
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    private static final long CATALOGO_NANOS = Duration.ofMinutes(1).toNanos();
    private final RepositorioCotacao cotacoes;
    private final RepositorioMercado mercado;
    private final SituacaoCotacao situacao;
    private final CacheCotacao cache;
    private final MeterRegistry metricas;
    private volatile List<Ativo> catalogo;
    private volatile long catalogoEm;

    @Inject
    public ConsultarCotacaoActor(RepositorioCotacao cotacoes, RepositorioMercado mercado, SituacaoCotacao situacao,
                                 CacheCotacao cache, MeterRegistry metricas) {
        this.cotacoes = cotacoes;
        this.mercado = mercado;
        this.situacao = situacao;
        this.cache = cache;
        this.metricas = metricas;
    }

    public LeituraMercado<List<AtivoResponseDTO>> listar() {
        List<AtivoResponseDTO> ativos = new ArrayList<>();
        String origem = LeituraMercado.REDIS;
        Long idade = null;
        for (Ativo ativo : catalogo()) {
            Lido lido = livroCotado(ativo.ticker());
            if (lido.origem().equals(LeituraMercado.POSTGRES)) origem = LeituraMercado.POSTGRES;
            idade = maior(idade, lido.idadeMs());
            ativos.add(resumo(ativo, lido.livro()));
        }
        return new LeituraMercado<>(List.copyOf(ativos), origem, idade);
    }

    public LeituraMercado<LivroResponseDTO> livro(String ticker) {
        catalogo().stream().filter(a -> a.ticker().equals(ticker)).findFirst()
                .orElseThrow(() -> new RegraMercadoException("ATIVO_DESCONHECIDO", "Ativo não cadastrado."));
        Lido lido = livroCotado(ticker);
        LivroCotado livro = lido.livro();
        if (livro == null) {
            return new LeituraMercado<>(new LivroResponseDTO(ticker, PRINCIPAL, "SEM_DADOS", null, null, null, null, null,
                    !situacao.emDia(), ORIGEM), lido.origem(), null);
        }
        var s = livro.sessao();
        return new LeituraMercado<>(new LivroResponseDTO(ticker, PRINCIPAL, estado(livro), lado(livro.compras()),
                lado(livro.vendas()), s.revisao(), s.sessao(), s.atualizadoEm(), defasado(livro), ORIGEM), lido.origem(), lido.idadeMs());
    }

    private record Lido(LivroCotado livro, String origem, Long idadeMs) { }

    /**
     * Redis primeiro (AP, ADR-022); ausência ou falha cai para o PostgreSQL, que repõe o snapshot no Redis pelo mesmo
     * compare-and-set da gravação. A idade é a distância até o último evento aplicado ao ativo.
     */
    private Lido livroCotado(String ticker) {
        Optional<CotacaoEmCache> doCache = cache.ler(ticker);
        if (doCache.isPresent()) {
            metricas.counter("prospero.cotacao.leituras", "origem", LeituraMercado.REDIS).increment();
            LivroCotado livro = doCache.get().livro();
            return new Lido(livro, LeituraMercado.REDIS, livro == null ? null : idade(livro));
        }
        metricas.counter("prospero.cotacao.leituras", "origem", LeituraMercado.POSTGRES).increment();
        LivroCotado doBanco = cotacoes.livro(ticker).orElse(null);
        if (doBanco != null) cache.gravar(doBanco);
        else cache.gravarAusente(ticker);
        return new Lido(doBanco, LeituraMercado.POSTGRES, doBanco == null ? null : idade(doBanco));
    }

    /** Catálogo estático em memória por 1 min; se o banco falhar, a cópia anterior continua servindo a leitura AP. */
    private List<Ativo> catalogo() {
        List<Ativo> atual = catalogo;
        if (atual != null && System.nanoTime() - catalogoEm < CATALOGO_NANOS) return atual;
        try {
            atual = List.copyOf(mercado.listarAtivos());
            catalogo = atual;
            catalogoEm = System.nanoTime();
            return atual;
        } catch (RuntimeException erro) {
            if (atual != null) return atual;
            throw erro;
        }
    }

    private static Long idade(LivroCotado livro) {
        return Math.max(0, Duration.between(livro.sessao().atualizadoEm(), Instant.now()).toMillis());
    }

    private static Long maior(Long a, Long b) {
        if (a == null) return b;
        return b == null ? a : Math.max(a, b);
    }

    private AtivoResponseDTO resumo(Ativo ativo, LivroCotado livro) {
        if (livro == null) {
            return new AtivoResponseDTO(ativo.ticker(), ativo.nome(), texto(ativo.precoReferencia()), texto(ativo.tick()), ativo.lote(),
                    null, null, null, null, null, null, null, null, "SEM_DADOS", null, null, null,
                    !situacao.emDia(), ORIGEM, null);
        }
        var s = livro.sessao();
        BigDecimal ultimo = s.ultimoPreco();
        BigDecimal variacao = ultimo == null ? null : ultimo.subtract(ativo.precoReferencia());
        BigDecimal percentual = variacao == null ? null : variacao.multiply(new BigDecimal("100"))
                .divide(ativo.precoReferencia(), 2, RoundingMode.HALF_EVEN);
        BigDecimal compra = melhor(livro.compras());
        BigDecimal venda = melhor(livro.vendas());
        return new AtivoResponseDTO(ativo.ticker(), ativo.nome(), texto(ativo.precoReferencia()), texto(ativo.tick()), ativo.lote(),
                texto(ultimo), texto(variacao), texto(percentual), texto(compra), texto(venda),
                compra == null || venda == null ? null : texto(venda.subtract(compra)), ultimo == null ? null : s.volumeQuantidade(),
                ultimo == null ? null : texto(s.volumeFinanceiro()), estado(livro), s.sessao(), s.revisao(), s.atualizadoEm(), defasado(livro), ORIGEM, texto(s.vwap()));
    }

    private boolean defasado(LivroCotado livro) {
        var s = livro.sessao();
        return !situacao.emDia() || s.encerrada() || s.sessao().isBefore(LocalDate.now(ZONA));
    }

    private static BigDecimal melhor(LadoCotado lado) {
        return lado.niveis().isEmpty() ? null : lado.niveis().getFirst().preco();
    }

    private static String estado(LivroCotado livro) {
        boolean compra = !livro.compras().niveis().isEmpty(), venda = !livro.vendas().niveis().isEmpty();
        if (compra && venda) return "COMPLETO";
        if (compra) return "SOMENTE_COMPRA";
        return venda ? "SOMENTE_VENDA" : "VAZIO";
    }

    private static LadoResponseDTO lado(LadoCotado lado) {
        return new LadoResponseDTO(lado.niveis().stream().map(n -> new NivelResponseDTO(texto(n.preco()),
                n.quantidade(), n.ofertas(), n.quantidadeAcumulada())).toList(), lado.niveisTotal(), lado.quantidadeTotal());
    }
}
