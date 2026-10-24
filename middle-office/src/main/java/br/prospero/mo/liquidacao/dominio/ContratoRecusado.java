package br.prospero.mo.liquidacao.dominio;

import java.util.UUID;

/**
 * Evento fora do contrato: vira recusa persistida, nunca bloqueia a partição. Não estende MensagemInvalidaException,
 * que no modo financeiro do consumo travaria o tópico. id e assinatura ficam nulos quando ainda não legíveis.
 */
public class ContratoRecusado extends RuntimeException {
    private final MotivoRecusa motivo;
    private final transient UUID id;
    private final String assinatura;

    public ContratoRecusado(MotivoRecusa motivo, UUID id, String assinatura) {
        super(motivo.name(), null, false, false);
        this.motivo = motivo;
        this.id = id;
        this.assinatura = assinatura;
    }

    public MotivoRecusa motivo() {
        return motivo;
    }

    public UUID id() {
        return id;
    }

    public String assinatura() {
        return assinatura;
    }
}
