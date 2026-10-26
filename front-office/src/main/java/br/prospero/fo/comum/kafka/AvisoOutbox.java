package br.prospero.fo.comum.kafka;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.jboss.logging.Logger;
import java.util.Objects;

/** Um callback sem dados por transação; o polling continua autoridade se o aviso falhar. */
@ApplicationScoped
public class AvisoOutbox {
    private static final Logger LOG = Logger.getLogger(AvisoOutbox.class);
    private static final Object CHAVE = new Object();
    private final TransactionSynchronizationRegistry registro;
    private final Runnable acordar;

    @Inject
    public AvisoOutbox(TransactionSynchronizationRegistry registro, RelayOutbox relay) {
        this(registro, relay::notificar);
    }

    AvisoOutbox(TransactionSynchronizationRegistry registro, Runnable acordar) {
        this.registro = Objects.requireNonNull(registro);
        this.acordar = Objects.requireNonNull(acordar);
    }

    public void registrar() {
        try {
            if (registro.getTransactionStatus() != Status.STATUS_ACTIVE || registro.getResource(CHAVE) != null) return;
            registro.putResource(CHAVE, Boolean.TRUE);
            registro.registerInterposedSynchronization(new Synchronization() {
                @Override public void beforeCompletion() { }
                @Override public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) acordarSemPropagar();
                }
            });
        } catch (RuntimeException erro) {
            LOG.warnf("Aviso da outbox indisponivel; recuperacao por polling: %s", erro.getClass().getSimpleName());
        }
    }

    private void acordarSemPropagar() {
        try {
            acordar.run();
        } catch (RuntimeException erro) {
            LOG.warnf("Aviso apos commit falhou; outbox preservada: %s", erro.getClass().getSimpleName());
        }
    }
}
