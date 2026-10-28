package br.prospero.bo.liquidacao.actor;

import br.prospero.bo.liquidacao.dominio.MovimentoInvalidoException;
import br.prospero.bo.liquidacao.dto.MovimentoResultadoDTO;
import br.prospero.bo.liquidacao.jpa.LiquidacaoJpa;
import br.prospero.bo.posicoes.dominio.SituacaoPosicoes;
import br.prospero.bo.posicoes.dominio.ProjecaoIncoerenteException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.ZoneId;

@ApplicationScoped
public class MovimentoActor {
    @Inject LiquidacaoJpa liquidacao;
    @Inject SituacaoPosicoes situacao;
    public MovimentoResultadoDTO avancar(LocalDate alvo, String sub, String jti) {
        if (!situacao.semLag()) throw new MovimentoInvalidoException(503, "LIQUIDACAO_INDISPONIVEL");
        try {
            return liquidacao.avancar(alvo, sub, jti, LocalDate.now(ZoneId.of("America/Sao_Paulo")));
        } catch (RuntimeException erro) {
            if (!LiquidacaoJpa.invariante(erro)) throw erro;
            // Fronteira transacional anterior já reverteu data, partidas e outbox.
            liquidacao.quarentena();
            throw new MovimentoInvalidoException(503, "LIQUIDACAO_INDISPONIVEL");
        }
    }
}
