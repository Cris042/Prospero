package br.prospero.mo.risco.dominio;

import java.math.BigDecimal;
import java.util.Objects;

/** Desfecho da reserva de uma venda (ADR-021): coberta pela custódia, com parte a descoberto e garantia, ou recusa. */
public sealed interface ReservaVenda {
    record Reservada(long descoberta, BigDecimal garantia) implements ReservaVenda {
        public Reservada {
            if (descoberta < 0 || (descoberta == 0) != (garantia == null) || garantia != null && garantia.signum() <= 0) {
                throw new IllegalArgumentException("Parte a descoberto e garantia incoerentes.");
            }
        }

        public static Reservada coberta() { return new Reservada(0, null); }
    }

    record Recusada(MotivoRejeicao motivo) implements ReservaVenda {
        public Recusada {
            Objects.requireNonNull(motivo);
        }
    }
}
