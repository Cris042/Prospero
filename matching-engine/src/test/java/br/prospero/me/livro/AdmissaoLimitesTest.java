package br.prospero.me.livro;

import br.prospero.me.livro.dominio.Admissao;
import br.prospero.me.livro.dominio.Evento;
import br.prospero.me.livro.dominio.MotivoRejeicao;
import br.prospero.me.livro.dominio.MotorMatching;
import br.prospero.me.livro.dominio.TipoOrdem;
import br.prospero.me.livro.dominio.Validade;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static br.prospero.me.livro.MotorMatchingTest.ALFA;
import static br.prospero.me.livro.MotorMatchingTest.compra;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AdmissaoLimitesTest {
    @Test
    void precosComEscalaExtremaSaoRejeitadosSemImpedirAdmissoesSeguintes() {
        MotorMatching motor = new MotorMatching(Map.of("ALFA3", ALFA));
        for (String extremo : List.of("1E-2147483647", "1E+2147483647")) {
            Admissao limit = compra("CONTA-002", TipoOrdem.LIMIT, Validade.DIA, 1, extremo, "10.00");
            assertEquals(List.of(new Evento.OrdemRejeitada(limit.ordem(), "ALFA3", MotivoRejeicao.PRECO_INVALIDO)),
                    motor.processar(limit));
            Admissao market = compra("CONTA-002", TipoOrdem.MARKET, Validade.IOC, 1, null, extremo);
            assertEquals(List.of(new Evento.OrdemRejeitada(market.ordem(), "ALFA3", MotivoRejeicao.PRECO_INVALIDO)),
                    motor.processar(market));
        }
        assertInstanceOf(Evento.OfertaNoLivro.class,
                motor.processar(compra("CONTA-002", TipoOrdem.LIMIT, Validade.DIA,
                        1, "1000000.00000000", "1E+6")).getLast());
    }
}
