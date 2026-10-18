package br.prospero.fo.ordens.dominio;

import java.math.BigDecimal;
import java.util.Objects;

/** Base aplicada ao túnel no registro da ordem; persistida para auditoria e para a RF04. */
public record BaseTunel(BigDecimal preco, OrigemBase origem) {
    public BaseTunel {
        Objects.requireNonNull(preco);
        Objects.requireNonNull(origem);
    }
}
