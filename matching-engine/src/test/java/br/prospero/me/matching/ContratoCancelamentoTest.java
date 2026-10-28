package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** RF10c2b: parser do CANCELAR_ORDEM contra as fixtures v5 compartilhadas e ausência de produtor de cancelamento. */
class ContratoCancelamentoTest {
    private static final String TOPICO = "matching.comandos.v1";

    @Test
    void manifestoDasFixturesConfere() throws Exception {
        ContratosV5.conferirManifesto();
    }

    @Test
    void comandosValidosViramAlvoComIdentidadeDerivada() throws Exception {
        var validos = ContratosV5.casos(TOPICO, false);
        assertEquals(4, validos.size());
        for (var caso : validos) {
            var comando = EnvelopeMatching.comando(caso.bytes(), caso.chave());
            var alvo = comando.cancelamento();
            assertNotNull(alvo, caso.nome());
            assertNull(comando.admissao());
            assertEquals(UUID.nameUUIDFromBytes(("cancelamento:" + alvo.ordem()).getBytes(StandardCharsets.UTF_8)), comando.id());
            assertEquals(alvo.sessao(), comando.sessao());
            assertEquals("ALFA3", alvo.ticker());
        }
    }

    @Test
    void corpusNegativoFalhaFechadoComMensagemEstatica() throws Exception {
        var invalidos = ContratosV5.casos(TOPICO, true);
        assertTrue(invalidos.size() >= 15, "corpus negativo do comando");
        for (var caso : invalidos) {
            var erro = assertThrows(IllegalArgumentException.class,
                    () -> EnvelopeMatching.comando(caso.bytes(), caso.chave()), caso.nome());
            assertEquals("Envelope matching inválido.", erro.getMessage(), caso.nome());
        }
    }

    @Test
    void tickerDiferenteDaChaveKafkaEhInvalido() throws Exception {
        var valido = ContratosV5.casos(TOPICO, false).getFirst();
        assertThrows(IllegalArgumentException.class, () -> EnvelopeMatching.comando(valido.bytes(), "BETA3"));
    }

    @Test
    void historicoDeAdmissaoV1aV3ContinuaValidoNoMesmoParser() throws Exception {
        for (int v = 1; v <= 3; v++) {
            try (var in = getClass().getResourceAsStream("/rf10a/historico-v" + v + "-comandos.ndjson")) {
                for (String linha : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                    assertNull(EnvelopeMatching.comando(linha.getBytes(StandardCharsets.UTF_8), "ALFA3").cancelamento());
                }
            }
        }
    }

    /** Nenhum caminho do ME publica em matching.comandos.v1 nem escreve CANCELAR_ORDEM: só o parser o conhece. */
    @Test
    void nenhumProdutorDoMeEmiteCancelarOrdem() throws Exception {
        try (Stream<Path> fontes = Files.walk(Path.of("src", "main", "java"))) {
            for (Path fonte : fontes.filter(p -> p.toString().endsWith(".java")).toList()) {
                String texto = Files.readString(fonte);
                if (texto.contains("CANCELAR_ORDEM")) {
                    assertTrue(fonte.endsWith(Path.of("matching", "helper", "EnvelopeMatching.java")), fonte.toString());
                    assertFalse(texto.contains("put(\"tipo\", \"CANCELAR_ORDEM\")"), "parser não serializa o comando");
                }
                assertFalse(texto.contains("ProducerRecord<String, byte[]>(EnvelopeMatching.COMANDOS"), fonte.toString());
            }
        }
    }
}
