package br.prospero.bo.razao.actor;

import br.prospero.bo.posicoes.dominio.AcessoPosicoesException;
import br.prospero.bo.posicoes.dominio.SituacaoPosicoes;
import br.prospero.bo.razao.dominio.*;
import br.prospero.bo.razao.dto.NegocioDetalheDTO;
import br.prospero.bo.razao.dto.PaginaNegociosDTO;
import br.prospero.bo.razao.dto.PaginaRazaoDTO;
import br.prospero.bo.razao.helper.CursorRazao;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Conta sempre do token; leitura só com a projeção em dia, conferida antes e depois do snapshot. */
@ApplicationScoped
public class ConsultarRazaoActor {
    private static final Set<String> CONTAS = Set.of("CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004", "CONTA-005");
    private static final Pattern NEGOCIO = Pattern.compile("(ALFA3|BETA3|GAMA3|DELTA3|OMEGA3)-[0-9]{4}-[0-9]{2}-[0-9]{2}-[1-9][0-9]{0,9}");
    private static final int LIMITE_PADRAO = 50;
    private static final int LIMITE_MAXIMO = 100;
    private final ConsultaRazao consulta;
    private final SituacaoPosicoes situacao;
    @Inject public ConsultarRazaoActor(ConsultaRazao consulta, SituacaoPosicoes situacao) {
        this.consulta = consulta;
        this.situacao = situacao;
    }

    public PaginaNegociosDTO negocios(String conta, String cursor, String limite) {
        exigirConta(conta);
        int tamanho = limite(limite);
        long antesDe = cursor == null ? Long.MAX_VALUE : CursorRazao.decodificar("negocios", conta, cursor);
        List<ParticipacaoNegocio> linhas = emDia(() -> consulta.negocios(conta, antesDe, tamanho + 1));
        boolean haMais = linhas.size() > tamanho;
        List<ParticipacaoNegocio> pagina = haMais ? linhas.subList(0, tamanho) : linhas;
        String proximo = haMais ? CursorRazao.codificar("negocios", conta, pagina.getLast().sequencia()) : null;
        return new PaginaNegociosDTO(conta, pagina.stream().map(ConsultarRazaoActor::dto).toList(), proximo);
    }

    public NegocioDetalheDTO negocio(String conta, String id) {
        exigirConta(conta);
        if (id == null || !NEGOCIO.matcher(id).matches()) {
            emDia(List::of);
            throw new NegocioNaoEncontradoException();
        }
        List<ParticipacaoNegocio> lados = emDia(() -> consulta.negocio(conta, id));
        if (lados.isEmpty()) throw new NegocioNaoEncontradoException();
        ParticipacaoNegocio p = lados.getFirst();
        return new NegocioDetalheDTO(conta, p.negocio(), p.ticker(), p.sessao().toString(), p.liquidacao().toString(),
                p.preco().toPlainString(), Long.toString(p.quantidade()), p.valor().toPlainString(),
                lados.stream().map(l -> new NegocioDetalheDTO.LadoDTO(l.lado().name(), l.ordem().toString(),
                        l.estado().name())).toList());
    }

    public PaginaRazaoDTO razao(String conta, String cursor, String limite) {
        exigirConta(conta);
        int tamanho = limite(limite);
        long antesDe = cursor == null ? Long.MAX_VALUE : CursorRazao.decodificar("razao", conta, cursor);
        List<LancamentoRazao> linhas = emDia(() -> consulta.lancamentos(conta, antesDe, tamanho + 1));
        boolean haMais = linhas.size() > tamanho;
        List<LancamentoRazao> pagina = haMais ? linhas.subList(0, tamanho) : linhas;
        String proximo = haMais ? CursorRazao.codificar("razao", conta, pagina.getLast().sequencia()) : null;
        return new PaginaRazaoDTO(conta, pagina.stream().map(ConsultarRazaoActor::dto).toList(), proximo);
    }

    private static void exigirConta(String conta) {
        if (!CONTAS.contains(conta == null ? "" : conta)) throw new AcessoPosicoesException();
    }

    private static int limite(String texto) {
        if (texto == null) return LIMITE_PADRAO;
        if (!texto.matches("[0-9]{1,3}")) throw new ConsultaInvalidaException();
        int valor = Integer.parseInt(texto);
        if (valor < 1 || valor > LIMITE_MAXIMO) throw new ConsultaInvalidaException();
        return valor;
    }

    private <T> T emDia(Supplier<T> leitura) {
        if (!situacao.emDia()) throw new RazaoIndisponivelException();
        T resultado;
        try { resultado = leitura.get(); }
        catch (PersistenceException erro) { throw new RazaoIndisponivelException(); }
        if (!situacao.emDia()) throw new RazaoIndisponivelException();
        return resultado;
    }

    private static PaginaNegociosDTO.NegocioDTO dto(ParticipacaoNegocio p) {
        return new PaginaNegociosDTO.NegocioDTO(p.negocio(), p.ticker(), p.lado().name(), p.ordem().toString(),
                p.sessao().toString(), p.liquidacao().toString(), p.preco().toPlainString(), Long.toString(p.quantidade()),
                p.valor().toPlainString(), p.estado().name());
    }

    private static PaginaRazaoDTO.LancamentoDTO dto(LancamentoRazao l) {
        Partida p = l.partida();
        return new PaginaRazaoDTO.LancamentoDTO(l.transacao().toString(), l.negocio(), l.ticker(), l.lado().name(),
                l.momento().name(), l.data().toString(), p.conta().name(), p.natureza().name(), p.montante().unidade(),
                p.montante().texto());
    }
}
