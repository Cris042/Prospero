package br.prospero.fo.mercado.rest;

import br.prospero.fo.mercado.actor.ConsultarMercadoActor;
import br.prospero.fo.mercado.dto.AtivoResponseDTO;
import br.prospero.fo.mercado.dto.LeituraMercado;
import br.prospero.fo.mercado.dto.LivroResponseDTO;
import br.prospero.fo.comum.dto.ProblemaDTO;
import br.prospero.fo.comum.rest.Problemas;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/ativos")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("mercado:leitura")
@SecurityRequirement(name = "jwt")
@Tag(name = "Mercado", description = "Consulta de ativos fictícios e livro agregado (RF01).")
public class AtivosResource {
    private final ConsultarMercadoActor actor;

    @Inject
    public AtivosResource(ConsultarMercadoActor actor) {
        this.actor = actor;
    }

    @GET
    @Operation(summary = "Listar os ativos com indicadores da sessão")
    @APIResponse(responseCode = "200", description = "Cinco ativos com indicadores; ausentes são null.",
            content = @Content(schema = @Schema(implementation = AtivoResponseDTO.class, type = SchemaType.ARRAY)))
    @APIResponse(responseCode = "400", description = "Namespace inválido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.")
    @APIResponse(responseCode = "403", description = "Token sem escopo mercado:leitura.")
    public Response listar(@Parameter(description = "UUID do namespace sintético; ausente usa o principal.")
                           @QueryParam("namespace") String namespace) {
        return resposta(actor.listar(ParametrosMercado.namespace(namespace, false)));
    }

    @GET
    @Path("/{ticker}/livro")
    @Operation(summary = "Consultar o livro agregado em cinco níveis por lado")
    @APIResponse(responseCode = "200", description = "Livro agregado; sem snapshot retorna SEM_DADOS.",
            content = @Content(schema = @Schema(implementation = LivroResponseDTO.class)))
    @APIResponse(responseCode = "400", description = "Namespace inválido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "404", description = "Ativo desconhecido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.")
    @APIResponse(responseCode = "403", description = "Token sem escopo mercado:leitura.")
    public Response livro(@PathParam("ticker") String ticker, @QueryParam("namespace") String namespace) {
        return resposta(actor.livro(ParametrosMercado.namespace(namespace, false), ParametrosMercado.ticker(ticker)));
    }

    /** ADR-022: X-Origem-Dados (REDIS ou POSTGRES) e X-Idade-Dados-Ms (desde o último evento aplicado). */
    private static Response resposta(LeituraMercado<?> leitura) {
        var resposta = Response.ok(leitura.corpo()).header("Cache-Control", "no-store").header("X-Origem-Dados", leitura.origem());
        if (leitura.idadeMs() != null) resposta.header("X-Idade-Dados-Ms", leitura.idadeMs());
        return resposta.build();
    }
}
