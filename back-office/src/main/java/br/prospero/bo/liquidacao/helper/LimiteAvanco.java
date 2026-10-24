package br.prospero.bo.liquidacao.helper;

import br.prospero.bo.liquidacao.dominio.MovimentoInvalidoException;
import br.prospero.bo.posicoes.dominio.ProjecaoIncoerenteException;
import br.prospero.bo.razao.dominio.Calendario;
import java.time.LocalDate;

public final class LimiteAvanco {
    private LimiteAvanco() { }
    public static void validar(Calendario calendario, LocalDate atual, LocalDate alvo) {
        if (alvo == null) throw new MovimentoInvalidoException(400,"DATA_INVALIDA");
        if (alvo.isBefore(atual)) throw new MovimentoInvalidoException(409,"DATA_RETROATIVA");
        try {
            calendario.util(atual);
            calendario.util(alvo);
            int uteis = 0;
            for (LocalDate dia = atual; dia.isBefore(alvo);) {
                dia = dia.plusDays(1);
                if (calendario.util(dia) && ++uteis > 10)
                    throw new MovimentoInvalidoException(422,"AVANCO_EXCESSIVO");
            }
        } catch (ProjecaoIncoerenteException erro) {
            throw new MovimentoInvalidoException(422,"CALENDARIO_INSUFICIENTE");
        }
    }
}
