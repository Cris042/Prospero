package br.prospero.fo.ordens.helper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** SHA-256 de uma forma canônica do pedido já normalizado; 10.1 e 10.10 geram a mesma assinatura. */
public final class AssinaturaEnvio {
    private AssinaturaEnvio() {
    }

    public static String hash(Object... campos) {
        String canonico = Stream.of(campos).map(AssinaturaEnvio::texto).collect(Collectors.joining("|", "v1|", ""));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonico.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException erro) {
            throw new IllegalStateException("SHA-256 indisponível.", erro);
        }
    }

    private static String texto(Object campo) {
        return campo instanceof BigDecimal decimal ? decimal.setScale(2).toPlainString() : Objects.toString(campo, "∅");
    }
}
