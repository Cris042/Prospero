package br.prospero.bo.sistema.rest;

import br.prospero.bo.sistema.actor.ConsultarSistemaActor;
import br.prospero.bo.sistema.dto.SistemaResponseDTO;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

@Path("/api/v1/sistema")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Sistema", description = "Identidade da fundação técnica local.")
public class SistemaResource {
    private static final Logger LOG = Logger.getLogger(SistemaResource.class);
    private final ConsultarSistemaActor actor;

    @Inject
    public SistemaResource(ConsultarSistemaActor actor) {
        this.actor = actor;
    }

    @GET
    @Operation(summary = "Consultar a identidade e etapa do serviço",
            description = "HTTP 200 confirma a consulta. Use /q/health/ready para verificar dependências.")
    @APIResponse(responseCode = "200", description = "Identidade e etapa de implementação do serviço.",
            content = @Content(schema = @Schema(implementation = SistemaResponseDTO.class)))
    public Response consultar() {
        LOG.info("Identidade da fundação consultada.");
        return Response.ok(actor.consultar()).header("Cache-Control", "no-store").build();
    }
}
