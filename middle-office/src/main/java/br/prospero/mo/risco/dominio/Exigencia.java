package br.prospero.mo.risco.dominio;

import java.math.BigDecimal;
import java.util.Objects;

/** Recurso que a intenção precisa reservar, ou a recusa antes de tocar em saldo. */
public sealed interface Exigencia {
    record Caixa(BigDecimal valor) implements Exigencia {
        public Caixa {
            if (valor == null || valor.signum() <= 0) {
                throw new IllegalArgumentException("Valor a reservar deve ser positivo.");
            }
        }
    }

    record Custodia(long quantidade) implements Exigencia {
        public Custodia {
            if (quantidade <= 0) {
                throw new IllegalArgumentException("Quantidade a reservar deve ser positiva.");
            }
        }
    }

    record Recusa(MotivoRejeicao motivo) implements Exigencia {
        public Recusa {
            Objects.requireNonNull(motivo);
        }
    }
}
