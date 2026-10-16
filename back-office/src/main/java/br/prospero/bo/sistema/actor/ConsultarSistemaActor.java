package br.prospero.bo.sistema.actor;

import br.prospero.bo.sistema.dto.SistemaResponseDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ConsultarSistemaActor {
    private final String nome;
    private final String versao;

    @Inject
    public ConsultarSistemaActor(
            @ConfigProperty(name = "quarkus.application.name") String nome,
            @ConfigProperty(name = "quarkus.application.version") String versao) {
        this.nome = nome;
        this.versao = versao;
    }

    public SistemaResponseDTO consultar() {
        return new SistemaResponseDTO(nome, versao, "FUNDAÇÃO");
    }
}
