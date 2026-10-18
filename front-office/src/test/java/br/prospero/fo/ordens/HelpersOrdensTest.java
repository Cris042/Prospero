package br.prospero.fo.ordens;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.RepositorioOrdens.Cursor;
import br.prospero.fo.ordens.dominio.TipoOrdem;
import br.prospero.fo.ordens.dominio.Validade;
import br.prospero.fo.ordens.helper.AssinaturaEnvio;
import br.prospero.fo.ordens.helper.CursorOrdens;
import br.prospero.fo.ordens.helper.EntradaOrdem;
import br.prospero.fo.ordens.helper.PoliticaOrdem;
import br.prospero.fo.ordens.helper.SessaoNegociacao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HelpersOrdensTest {
    private static final BigDecimal P = new BigDecimal("10.00");

    private static BigDecimal limite(TipoOrdem tipo) {
        return tipo == TipoOrdem.LIMIT || tipo == TipoOrdem.STOP_LIMIT ? P : null;
    }

    private static BigDecimal gatilho(TipoOrdem tipo) {
        return tipo == TipoOrdem.STOP || tipo == TipoOrdem.STOP_LIMIT ? P : null;
    }

    @ParameterizedTest(name = "{0} {1} permitido={2}")
    @CsvSource({"MARKET,DIA,false", "MARKET,IOC,true", "MARKET,FOK,true",
            "LIMIT,DIA,true", "LIMIT,IOC,true", "LIMIT,FOK,true",
            "STOP,DIA,true", "STOP,IOC,false", "STOP,FOK,false",
            "STOP_LIMIT,DIA,true", "STOP_LIMIT,IOC,false", "STOP_LIMIT,FOK,false"})
    void matrizTipoValidadeCelulaACelula(TipoOrdem tipo, Validade validade, boolean permitido) {
        if (permitido) {
            assertDoesNotThrow(() -> PoliticaOrdem.validar(tipo, validade, limite(tipo), gatilho(tipo)));
        } else {
            assertEquals("COMBINACAO_INVALIDA", assertThrows(RegraNegocioException.class,
                    () -> PoliticaOrdem.validar(tipo, validade, limite(tipo), gatilho(tipo))).codigo());
        }
    }

    @Test
    void camposDePrecoExigidosEProibidosPorTipo() {
        assertEquals("CAMPO_EXIGIDO_PELO_TIPO", assertThrows(RegraNegocioException.class,
                () -> PoliticaOrdem.validar(TipoOrdem.LIMIT, Validade.DIA, null, null)).codigo());
        assertEquals("CAMPO_EXIGIDO_PELO_TIPO", assertThrows(RegraNegocioException.class,
                () -> PoliticaOrdem.validar(TipoOrdem.STOP_LIMIT, Validade.DIA, null, P)).codigo());
        assertEquals("CAMPO_EXIGIDO_PELO_TIPO", assertThrows(RegraNegocioException.class,
                () -> PoliticaOrdem.validar(TipoOrdem.STOP, Validade.DIA, null, null)).codigo());
        assertEquals("CAMPO_PROIBIDO_PELO_TIPO", assertThrows(RegraNegocioException.class,
                () -> PoliticaOrdem.validar(TipoOrdem.MARKET, Validade.IOC, P, null)).codigo());
        assertEquals("CAMPO_PROIBIDO_PELO_TIPO", assertThrows(RegraNegocioException.class,
                () -> PoliticaOrdem.validar(TipoOrdem.LIMIT, Validade.DIA, P, P)).codigo());
        assertEquals("CAMPO_PROIBIDO_PELO_TIPO", assertThrows(RegraNegocioException.class,
                () -> PoliticaOrdem.validar(TipoOrdem.STOP, Validade.DIA, P, P)).codigo());
    }

    @Test
    void ocoExigeStopEPrecosCoerentesComOLado() {
        BigDecimal alvo = new BigDecimal("11.00");
        BigDecimal stop = new BigDecimal("9.00");
        assertDoesNotThrow(() -> PoliticaOrdem.validarOco(LadoOrdem.VENDA, TipoOrdem.STOP, alvo, stop));
        assertDoesNotThrow(() -> PoliticaOrdem.validarOco(LadoOrdem.COMPRA, TipoOrdem.STOP_LIMIT, stop, alvo));
        assertEquals("OCO_PRECOS_INCOERENTES", assertThrows(RegraNegocioException.class,
                () -> PoliticaOrdem.validarOco(LadoOrdem.VENDA, TipoOrdem.STOP, stop, alvo)).codigo());
        assertEquals("OCO_PRECOS_INCOERENTES", assertThrows(RegraNegocioException.class,
                () -> PoliticaOrdem.validarOco(LadoOrdem.VENDA, TipoOrdem.STOP, stop, stop)).codigo());
        assertEquals("OCO_TIPO_INVALIDO", assertThrows(RegraNegocioException.class,
                () -> PoliticaOrdem.validarOco(LadoOrdem.VENDA, TipoOrdem.LIMIT, alvo, stop)).codigo());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "1.001", "1e3", "1,00", " 1", "10000000", "1000000.01", "+1"})
    void precoForaDaGramaticaOuLimite(String texto) {
        assertEquals("PRECO_INVALIDO", assertThrows(RegraNegocioException.class, () -> EntradaOrdem.preco(texto)).codigo());
    }

    @Test
    void precoNormalizaEscalaEAusenteContinuaNulo() {
        assertEquals("10.10", EntradaOrdem.preco("10.1").toPlainString());
        assertNull(EntradaOrdem.preco(null));
        assertEquals(1_000_000L, EntradaOrdem.quantidade(1_000_000L));
        for (long q : new long[]{0, -1, 1_000_001}) {
            assertEquals("QUANTIDADE_INVALIDA", assertThrows(RegraNegocioException.class,
                    () -> EntradaOrdem.quantidade(q)).codigo());
        }
    }

    @Test
    void sessaoDiaUsaDataCivilDeSaoPaulo() {
        // 02:59Z = 23:59 de 16/10 em São Paulo (UTC−3); 03:00Z já é 17/10.
        assertEquals(LocalDate.of(2026, 10, 16), SessaoNegociacao.data(Instant.parse("2026-10-17T02:59:59Z")));
        assertEquals(LocalDate.of(2026, 10, 17), SessaoNegociacao.data(Instant.parse("2026-10-17T03:00:00Z")));
    }

    @Test
    void assinaturaCanonicaIgnoraEscalaEDiferenciaConteudo() {
        assertEquals(AssinaturaEnvio.hash("ORDEM", "ALFA3", new BigDecimal("10.1")),
                AssinaturaEnvio.hash("ORDEM", "ALFA3", new BigDecimal("10.10")));
        assertNotEquals(AssinaturaEnvio.hash("ORDEM", "ALFA3", new BigDecimal("10.10")),
                AssinaturaEnvio.hash("ORDEM", "ALFA3", new BigDecimal("10.11")));
        assertNotEquals(AssinaturaEnvio.hash("ORDEM", null), AssinaturaEnvio.hash("ORDEM", "null"));
        assertEquals(64, AssinaturaEnvio.hash("x").length());
    }

    @Test
    void cursorOpacoIdaEVoltaERecusaAdulterado() {
        Cursor cursor = new Cursor(Instant.parse("2026-10-17T12:00:00.123456Z"), UUID.randomUUID());
        assertEquals(cursor, CursorOrdens.decodificar(CursorOrdens.codificar(cursor)));
        assertNull(CursorOrdens.decodificar(null));
        for (String invalido : new String[]{"abc", "%%%", "MS4yLnh5eg", "a".repeat(200)}) {
            RegraNegocioException erro = assertThrows(RegraNegocioException.class, () -> CursorOrdens.decodificar(invalido));
            assertEquals(Categoria.ENTRADA_INVALIDA, erro.categoria());
        }
    }
}
