package br.prospero.fo.mercado;

import br.prospero.fo.mercado.dominio.Ativo;
import br.prospero.fo.mercado.dominio.EstadoLivro;
import br.prospero.fo.mercado.dominio.Indicadores;
import br.prospero.fo.mercado.dominio.LadoAgregado;
import br.prospero.fo.mercado.dominio.Lado;
import br.prospero.fo.mercado.dominio.Negocio;
import br.prospero.fo.mercado.dominio.Oferta;
import br.prospero.fo.mercado.dominio.RegraMercadoException;
import br.prospero.fo.mercado.dominio.SnapshotMercado;
import br.prospero.fo.mercado.helper.AgregadorLivro;
import br.prospero.fo.mercado.helper.CalculadoraIndicadores;
import br.prospero.fo.mercado.helper.ConversorDecimal;
import br.prospero.fo.mercado.helper.ValidadorSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HelpersMercadoTest {
    private static final Ativo ALFA = new Ativo("ALFA3", "Alfa", new BigDecimal("10.00"), new BigDecimal("0.01"), 1);
    private static final Ativo LOTE100 = new Ativo("BETA3", "Beta", new BigDecimal("20.00"), new BigDecimal("0.05"), 100);

    private static Oferta oferta(Lado lado, String preco, long quantidade) {
        return new Oferta(lado, new BigDecimal(preco), quantidade);
    }

    private static SnapshotMercado snapshot(List<Oferta> ofertas, List<Negocio> negocios) {
        return new SnapshotMercado(UUID.randomUUID(), "ALFA3", 1, LocalDate.of(2026, 10, 17), Instant.EPOCH, ofertas, negocios);
    }

    @Test
    void agregaPorPrecoOrdenaCortaEmCincoEAcumula() {
        List<Oferta> ofertas = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            ofertas.add(oferta(Lado.COMPRA, "9.9" + i, 10));
        }
        ofertas.add(oferta(Lado.COMPRA, "9.96", 5));
        ofertas.add(oferta(Lado.VENDA, "10.10", 3));
        LadoAgregado compra = AgregadorLivro.agregar(ofertas, Lado.COMPRA);
        assertEquals(5, compra.niveis().size());
        assertEquals(7, compra.niveisTotal());
        assertEquals(75, compra.quantidadeTotal());
        assertEquals(new BigDecimal("9.96"), compra.niveis().getFirst().preco());
        assertEquals(15, compra.niveis().getFirst().quantidade());
        assertEquals(2, compra.niveis().getFirst().ofertas());
        assertEquals(55, compra.niveis().getLast().quantidadeAcumulada());
        assertEquals(new BigDecimal("9.92"), compra.niveis().getLast().preco());
    }

    @Test
    void vendasCrescentesENaoPreencheNiveisInexistentes() {
        LadoAgregado venda = AgregadorLivro.agregar(List.of(oferta(Lado.VENDA, "10.20", 1),
                oferta(Lado.VENDA, "10.05", 2)), Lado.VENDA);
        assertEquals(2, venda.niveis().size());
        assertEquals(new BigDecimal("10.05"), venda.niveis().getFirst().preco());
        assertEquals(3, venda.niveis().getLast().quantidadeAcumulada());
    }

    @Test
    void calculaIndicadoresComVariacaoAbsolutaPercentualEVolumes() {
        Indicadores i = CalculadoraIndicadores.calcular(ALFA, snapshot(
                List.of(oferta(Lado.COMPRA, "10.30", 100), oferta(Lado.VENDA, "10.40", 50)),
                List.of(new Negocio(new BigDecimal("10.20"), 100), new Negocio(new BigDecimal("10.35"), 300))));
        assertEquals(new BigDecimal("10.35"), i.ultimoPreco());
        assertEquals(new BigDecimal("0.35"), i.variacao());
        assertEquals(new BigDecimal("3.50"), i.variacaoPercentual());
        assertEquals(new BigDecimal("0.10"), i.spread());
        assertEquals(400L, i.volumeQuantidade());
        assertEquals(new BigDecimal("4125.00"), i.volumeFinanceiro());
        assertEquals(EstadoLivro.COMPLETO, i.estadoLivro());
    }

    @Test
    void percentualUsaHalfEvenEmDuasCasas() {
        Ativo tres = new Ativo("GAMA3", "Gama", new BigDecimal("30.00"), new BigDecimal("0.01"), 1);
        Indicadores i = CalculadoraIndicadores.calcular(tres, snapshot(List.of(),
                List.of(new Negocio(new BigDecimal("30.01"), 1))));
        assertEquals(new BigDecimal("0.03"), i.variacaoPercentual());
        // 0.01 / 40.00 × 100 = 0.025: HALF_EVEN resulta 0.02 (HALF_UP seria 0.03).
        Ativo quarenta = new Ativo("DELTA3", "Delta", new BigDecimal("40.00"), new BigDecimal("0.01"), 1);
        assertEquals(new BigDecimal("0.02"), CalculadoraIndicadores.calcular(quarenta, snapshot(List.of(),
                List.of(new Negocio(new BigDecimal("40.01"), 1)))).variacaoPercentual());
        assertEquals(new BigDecimal("-99.97"), CalculadoraIndicadores.calcular(tres, snapshot(List.of(),
                List.of(new Negocio(new BigDecimal("0.01"), 1)))).variacaoPercentual());
    }

    @Test
    void ausenciasNaoViramZero() {
        Indicadores semSnapshot = CalculadoraIndicadores.calcular(ALFA, null);
        assertEquals(EstadoLivro.SEM_DADOS, semSnapshot.estadoLivro());
        assertNull(semSnapshot.volumeQuantidade());
        Indicadores vazio = CalculadoraIndicadores.calcular(ALFA, snapshot(List.of(), List.of()));
        assertEquals(EstadoLivro.VAZIO, vazio.estadoLivro());
        assertNull(vazio.ultimoPreco());
        assertNull(vazio.variacao());
        assertNull(vazio.volumeFinanceiro());
        Indicadores unilateral = CalculadoraIndicadores.calcular(ALFA, snapshot(List.of(oferta(Lado.VENDA, "10.10", 1)), List.of()));
        assertEquals(EstadoLivro.SOMENTE_VENDA, unilateral.estadoLivro());
        assertNull(unilateral.spread());
        assertEquals(EstadoLivro.SOMENTE_COMPRA, CalculadoraIndicadores.calcular(ALFA,
                snapshot(List.of(oferta(Lado.COMPRA, "9.10", 1)), List.of())).estadoLivro());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "0.00", "-1.00", "1.001", "1e3", " 1.00", "1,00", "10000000", "1000000.01", "+1.00"})
    void recusaPrecosForaDaGramaticaOuLimite(String texto) {
        assertThrows(RegraMercadoException.class, () -> ConversorDecimal.preco(texto, ALFA.tick()));
    }

    @Test
    void exigeTickEMantemEscala() {
        assertEquals("10.10", ConversorDecimal.preco("10.1", ALFA.tick()).toPlainString());
        assertEquals("PRECO_FORA_DO_TICK", assertThrows(RegraMercadoException.class,
                () -> ConversorDecimal.preco("20.03", LOTE100.tick())).codigo());
        assertEquals("1000000.00", ConversorDecimal.preco("1000000", ALFA.tick()).toPlainString());
    }

    @Test
    void exigeQuantidadeInteiraPositivaMultiplaDoLote() {
        assertEquals("QUANTIDADE_FORA_DO_LOTE", assertThrows(RegraMercadoException.class,
                () -> ValidadorSnapshot.quantidade(150L, LOTE100)).codigo());
        for (Long invalida : new Long[]{null, 0L, -1L, 1_000_001L}) {
            assertEquals("QUANTIDADE_INVALIDA", assertThrows(RegraMercadoException.class,
                    () -> ValidadorSnapshot.quantidade(invalida, ALFA)).codigo());
        }
        assertEquals(200L, ValidadorSnapshot.quantidade(200L, LOTE100));
    }

    @Test
    void recusaLivroCruzadoOuTravadoELimites() {
        assertEquals("LIVRO_CRUZADO", assertThrows(RegraMercadoException.class, () -> ValidadorSnapshot.validar(
                List.of(oferta(Lado.COMPRA, "10.00", 1), oferta(Lado.VENDA, "10.00", 1)), List.of())).codigo());
        List<Oferta> muitas = new ArrayList<>();
        for (int i = 0; i <= ValidadorSnapshot.MAX_OFERTAS_POR_LADO; i++) {
            muitas.add(oferta(Lado.VENDA, "10.00", 1));
        }
        assertEquals("LIMITE_EXCEDIDO", assertThrows(RegraMercadoException.class,
                () -> ValidadorSnapshot.validar(muitas, List.of())).codigo());
    }
}
