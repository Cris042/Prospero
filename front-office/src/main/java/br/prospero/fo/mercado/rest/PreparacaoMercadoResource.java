package br.prospero.fo.mercado.rest;

import br.prospero.fo.mercado.actor.PrepararMercadoActor;
import br.prospero.fo.mercado.dominio.RegraMercadoException;
import br.prospero.fo.mercado.dto.PreparacaoResponseDTO;
import br.prospero.fo.comum.dto.ProblemaDTO;
import br.prospero.fo.comum.rest.Problemas;
import br.prospero.fo.mercado.dto.SnapshotRequestDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

@Path("/api/v1/preparacoes-mercado/{namespace}")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("mercado:preparacao")
@SecurityRequirement(name = "jwt")
@Tag(name = "Preparação de mercado", description = "Snapshots sintéticos locais por namespace; desabilitada fora do ambiente local.")
public class PreparacaoMercadoResource {
    private static final Logger LOG = Logger.getLogger(PreparacaoMercadoResource.class);
    private final PrepararMercadoActor actor;

    @Inject
    public PreparacaoMercadoResource(PrepararMercadoActor actor) {
        this.actor = actor;
    }

    @PUT
    @Path("/ativos/{ticker}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Substituir o snapshot sintético de um ativo")
    @APIResponse(responseCode = "200", description = "Snapshot validado e substituído atomicamente.",
            content = @Content(schema = @Schema(implementation = PreparacaoResponseDTO.class)))
    @APIResponse(responseCode = "400", description = "JSON, namespace ou campos inválidos.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "404", description = "Ativo desconhecido ou preparação desabilitada.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "422", description = "Preço, tick, lote, limites ou livro cruzado.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "409", description = "Namespace principal reservado à cotação real.")
    @APIResponse(responseCode = "429", description = "Limite de namespaces atingido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.")
    @APIResponse(responseCode = "403", description = "Token sem escopo mercado:preparacao.")
    public Response preparar(@PathParam("namespace") String namespace, @PathParam("ticker") String ticker,
                             @NotNull @Valid SnapshotRequestDTO pedido) {
        exigirHabilitada();
        PreparacaoResponseDTO resposta = actor.preparar(ParametrosMercado.namespace(namespace, true), ParametrosMercado.ticker(ticker), pedido);
        LOG.infof("Snapshot sintético preparado: ticker=%s revisao=%d", resposta.ticker(), resposta.revisao());
        return Response.ok(resposta).header("Cache-Control", "no-store").build();
    }

    @DELETE
    @Operation(summary = "Remover todos os snapshots do namespace (idempotente)")
    @APIResponse(responseCode = "204", description = "Namespace sintético limpo; repetir também retorna 204.")
    @APIResponse(responseCode = "409", description = "Namespace principal reservado à cotação real.")
    @APIResponse(responseCode = "400", description = "Namespace inválido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "404", description = "Preparação desabilitada.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.")
    @APIResponse(responseCode = "403", description = "Token sem escopo mercado:preparacao.")
    public Response limpar(@PathParam("namespace") String namespace) {
        exigirHabilitada();
        actor.limpar(ParametrosMercado.namespace(namespace, true));
        return Response.noContent().header("Cache-Control", "no-store").build();
    }

    private void exigirHabilitada() {
        if (!actor.habilitada()) {
            throw new RegraMercadoException("PREPARACAO_DESABILITADA", "Preparação sintética desabilitada neste ambiente.");
        }
    }
}
