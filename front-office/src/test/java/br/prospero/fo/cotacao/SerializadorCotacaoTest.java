package br.prospero.fo.cotacao;

import br.prospero.fo.cotacao.dominio.LadoCotado;
import br.prospero.fo.cotacao.dominio.LivroCotado;
import br.prospero.fo.cotacao.dominio.NivelCotado;
import br.prospero.fo.cotacao.dominio.SessaoCotacao;
import br.prospero.fo.cotacao.helper.SerializadorCotacao;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-022: o snapshot do Redis preserva escala exata e a versão ordena por sessão e depois por revisão. */
public class SerializadorCotacaoTest {
    public static LivroCotado livro(LocalDate dia, long revisao, String ultimo) {
        var sessao = new SessaoCotacao("ALFA3", dia, ultimo == null ? null : new BigDecimal(ultimo), 3,
                new BigDecimal("30.020"), revisao, Instant.parse("2026-10-28T12:00:00.123456Z"), false);
        return new LivroCotado(sessao, new LadoCotado(List.of(new NivelCotado(new BigDecimal("9.90"), 7, 2, 7)), 1, 7),
                new LadoCotado(List.of(), 0, 0));
    }

    @Test
    void idaEVoltaPreservaDecimaisNulosENiveis() {
        var original = livro(LocalDate.of(2026, 10, 28), 5, "10.10");
        assertEquals(original, SerializadorCotacao.ler(SerializadorCotacao.serializar(original)));
        var semNegocio = livro(LocalDate.of(2026, 10, 28), 0, null);
        var lido = SerializadorCotacao.ler(SerializadorCotacao.serializar(semNegocio));
        assertNull(lido.sessao().ultimoPreco());
        assertEquals("30.020", lido.sessao().volumeFinanceiro().toPlainString(), "escala original, sem double");
        assertEquals("9.90", lido.compras().niveis().getFirst().preco().toPlainString());
        assertThrows(IllegalArgumentException.class, () -> SerializadorCotacao.ler("{\"ticker\":1}"));
    }

    @Test
    void versaoOrdenaLexicograficamenteComoSessaoERevisao() {
        LocalDate dia = LocalDate.of(2026, 10, 28);
        String v9 = SerializadorCotacao.versao(livro(dia, 9, null).sessao());
        String v10 = SerializadorCotacao.versao(livro(dia, 10, null).sessao());
        String amanha = SerializadorCotacao.versao(livro(dia.plusDays(1), 1, null).sessao());
        assertTrue(v9.compareTo(v10) < 0, "9 < 10 também como texto");
        assertTrue(v10.compareTo(amanha) < 0, "sessão nova vence revisão alta da anterior");
        assertEquals(v10.length(), amanha.length());
        assertEquals(v10, SerializadorCotacao.versao(livro(dia, 10, "1.00").sessao()));
    }
}
