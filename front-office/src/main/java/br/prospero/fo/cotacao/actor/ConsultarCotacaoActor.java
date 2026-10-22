package br.prospero.fo.cotacao.actor;

import br.prospero.fo.cotacao.dominio.LadoCotado;
import br.prospero.fo.cotacao.dominio.LivroCotado;
import br.prospero.fo.cotacao.dominio.RepositorioCotacao;
import br.prospero.fo.cotacao.dominio.SituacaoCotacao;
import br.prospero.fo.mercado.dominio.Ativo;
import br.prospero.fo.mercado.dominio.RegraMercadoException;
import br.prospero.fo.mercado.dominio.RepositorioMercado;
import br.prospero.fo.mercado.dto.AtivoResponseDTO;
import br.prospero.fo.mercado.dto.LadoResponseDTO;
import br.prospero.fo.mercado.dto.LivroResponseDTO;
import br.prospero.fo.mercado.dto.NivelResponseDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static br.prospero.fo.mercado.helper.ConversorDecimal.texto;

/** Converte apenas indicadores e profundidade agregada; nenhuma identidade de oferta alcança a API. */
@ApplicationScoped
public class ConsultarCotacaoActor {
    public static final String ORIGEM = "MATCHING";
    private static final UUID PRINCIPAL = new UUID(0, 0);
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    private final RepositorioCotacao cotacoes;
    private final RepositorioMercado mercado;
    private final SituacaoCotacao situacao;

    @Inject
    public ConsultarCotacaoActor(RepositorioCotacao cotacoes, RepositorioMercado mercado, SituacaoCotacao situacao) {
        this.cotacoes = cotacoes;
        this.mercado = mercado;
        this.situacao = situacao;
    }

    public List<AtivoResponseDTO> listar() {
        return mercado.listarAtivos().stream().map(a -> resumo(a, cotacoes.livro(a.ticker()).orElse(null))).toList();
    }

    public LivroResponseDTO livro(String ticker) {
        mercado.buscarAtivo(ticker).orElseThrow(() -> new RegraMercadoException("ATIVO_DESCONHECIDO", "Ativo não cadastrado."));
        LivroCotado livro = cotacoes.livro(ticker).orElse(null);
        if (livro == null) {
            return new LivroResponseDTO(ticker, PRINCIPAL, "SEM_DADOS", null, null, null, null, null, !situacao.emDia(), ORIGEM);
        }
        var s = livro.sessao();
        return new LivroResponseDTO(ticker, PRINCIPAL, estado(livro), lado(livro.compras()), lado(livro.vendas()),
                s.revisao(), s.sessao(), s.atualizadoEm(), defasado(livro), ORIGEM);
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
