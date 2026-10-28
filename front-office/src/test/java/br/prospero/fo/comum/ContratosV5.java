package br.prospero.fo.comum;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixtures v5 compartilhadas na raiz do repositório; cada teste confere o manifesto antes de usar os bytes. */
public final class ContratosV5 {
    public static final Path RAIZ = Path.of("..", "contratos", "v5");

    private ContratosV5() { }

    public record Caso(String nome, String chave, byte[] bytes) { }

    public static byte[] ler(String relativo) throws IOException {
        conferirManifesto();
        return Files.readAllBytes(RAIZ.resolve(relativo));
    }

    /** Casos de um tópico: válidos em {@code topico/*}, inválidos em {@code invalidos/topico/*}. */
    public static List<Caso> casos(String topico, boolean invalidos) throws IOException {
        conferirManifesto();
        Path base = invalidos ? RAIZ.resolve("invalidos").resolve(topico) : RAIZ.resolve(topico);
        try (Stream<Path> arquivos = Files.walk(base)) {
            return arquivos.filter(Files::isRegularFile).sorted().map(p -> {
                try {
                    return new Caso(p.getFileName().toString(), p.getParent().getFileName().toString(), Files.readAllBytes(p));
                } catch (IOException erro) {
                    throw new IllegalStateException(erro);
                }
            }).toList();
        }
    }

    public static void conferirManifesto() throws IOException {
        Map<String, String> listados = new TreeMap<>();
        for (String linha : Files.readAllLines(RAIZ.resolve("MANIFESTO.sha256"), StandardCharsets.UTF_8)) {
            String[] partes = linha.split("  ", 2);
            assertEquals(2, partes.length, "linha do manifesto");
            listados.put(partes[1], partes[0]);
        }
        Map<String, String> reais = new TreeMap<>();
        try (Stream<Path> arquivos = Files.walk(RAIZ)) {
            for (Path p : arquivos.filter(Files::isRegularFile).toList()) {
                String relativo = RAIZ.relativize(p).toString().replace('\\', '/');
                if (relativo.equals("MANIFESTO.sha256")) continue;
                try {
                    reais.put(relativo, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
                } catch (java.security.NoSuchAlgorithmException erro) {
                    throw new IllegalStateException(erro);
                }
            }
        }
        assertTrue(!listados.isEmpty(), "manifesto vazio");
        assertEquals(listados, reais, "fixtures divergem do manifesto SHA-256");
    }
}
