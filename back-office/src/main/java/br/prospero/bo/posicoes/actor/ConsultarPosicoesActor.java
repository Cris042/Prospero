package br.prospero.bo.posicoes.actor;

import br.prospero.bo.posicoes.dominio.*;
import br.prospero.bo.posicoes.dto.PosicoesResponseDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

@ApplicationScoped
public class ConsultarPosicoesActor {
    private final ConsultaPosicoes consulta;
    private final SituacaoPosicoes situacao;
    @Inject public ConsultarPosicoesActor(ConsultaPosicoes consulta, SituacaoPosicoes situacao) {
        this.consulta = consulta;
        this.situacao = situacao;
    }

    public PosicoesResponseDTO consultar(String conta) {
        if (!Set.of("CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004", "CONTA-005").contains(conta == null ? "" : conta)) {
            throw new AcessoPosicoesException();
        }
        if (!situacao.emDia()) throw new PosicoesIndisponiveisException();
        try { return snapshot(conta); }
        catch (jakarta.persistence.PersistenceException erro) { throw new PosicoesIndisponiveisException(); }
        catch (IllegalStateException erro) {
            if ("ORCAMENTO_RACIONAL".equals(erro.getMessage())) throw new PosicoesIndisponiveisException();
            throw erro;
        }
    }

    private PosicoesResponseDTO snapshot(String conta) {
        var estados = consulta.consultar(conta);
        if (estados.size() != 5 || !situacao.emDia()) throw new PosicoesIndisponiveisException();
        LocalDate hoje = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
        var resposta = new PosicoesResponseDTO(conta, "NEGOCIOS_DESDE_ORIGEM", true,
                estados.stream().map(e -> dto(e, hoje)).toList());
        if (!situacao.emDia()) throw new PosicoesIndisponiveisException();
        return resposta;
    }

    private static PosicoesResponseDTO.PosicaoDTO dto(EstadoPosicao e, LocalDate hoje) {
        Posicao p = e.posicao();
        String lado = p.quantidade() == 0 ? "ZERADA" : p.quantidade() > 0 ? "COMPRADA" : "VENDIDA";
        Racional naoRealizado = p.naoRealizado(e.cotacao());
        boolean defasada = e.cotacao() == null || e.sessao() == null || !e.sessao().equals(hoje) || e.encerrada();
        return new PosicoesResponseDTO.PosicaoDTO(e.ticker(), Long.toString(p.quantidade()), lado,
                p.medio() == null ? null : p.medio().formatar(), p.realizado().formatar(),
                naoRealizado == null ? null : naoRealizado.formatar(), e.cotacao() == null ? null : e.cotacao().toPlainString(),
                e.sessao() == null ? null : e.sessao().toString(), defasada, e.revisao(),
                e.atualizadaEm() == null ? null : e.atualizadaEm().toString());
    }
}
