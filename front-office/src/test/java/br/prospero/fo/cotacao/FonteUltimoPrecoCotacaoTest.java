package br.prospero.fo.cotacao;

import br.prospero.fo.cotacao.dominio.*;
import br.prospero.fo.ordens.actor.FonteUltimoPrecoCotacao;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FonteUltimoPrecoCotacaoTest {
    @Test
    void retornaSomenteUltimoRealDaSessaoSolicitada() {
        var repositorio = new RepositorioCotacaoMemoria();
        var fonte = new FonteUltimoPrecoCotacao(repositorio);
        LocalDate dia = LocalDate.of(2026, 10, 22);
        assertTrue(fonte.ultimoNegocio("ALFA3", dia).isEmpty());
        var vazio = new LadoCotado(List.of(), 0, 0);
        repositorio.preparar(new LivroCotado(new SessaoCotacao("ALFA3", dia, null, 0,
                BigDecimal.ZERO, 1, Instant.EPOCH, false), vazio, vazio));
        assertTrue(fonte.ultimoNegocio("ALFA3", dia).isEmpty(), "Oferta sem negócio não altera base");
        repositorio.preparar(new LivroCotado(new SessaoCotacao("ALFA3", dia, new BigDecimal("10.25"), 1,
                new BigDecimal("10.25"), 2, Instant.EPOCH, false), vazio, vazio));
        assertEquals(new BigDecimal("10.25"), fonte.ultimoNegocio("ALFA3", dia).orElseThrow());
        assertTrue(fonte.ultimoNegocio("ALFA3", dia.plusDays(1)).isEmpty());
        assertTrue(fonte.ultimoNegocio("BETA3", dia).isEmpty());
    }
}
