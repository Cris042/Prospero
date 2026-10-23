package br.prospero.bo.posicoes.kafka;

import br.prospero.bo.posicoes.dominio.SituacaoPosicoes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class ProntidaoPosicoes implements HealthCheck {
    private final SituacaoPosicoes situacao;
    @Inject public ProntidaoPosicoes(SituacaoPosicoes situacao) { this.situacao = situacao; }
    @Override public HealthCheckResponse call() {
        return HealthCheckResponse.named("Projeção de posições").status(situacao.emDia()).build();
    }
}
