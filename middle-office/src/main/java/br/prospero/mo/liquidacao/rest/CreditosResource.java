package br.prospero.mo.liquidacao.rest;

import br.prospero.mo.liquidacao.actor.ConsultarCreditoActor;
import br.prospero.mo.liquidacao.dto.CreditosDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

@Path("/api/v1/risco/liquidacoes/{negocio}")
@Produces("application/json")
@RolesAllowed("risco:leitura")
@SecurityRequirement(name="jwt")
public class CreditosResource {
    @Inject ConsultarCreditoActor actor;
    @Inject JsonWebToken token;
    @GET
    @Operation(summary="Consultar expectativa e aplicação do crédito da própria conta")
    @APIResponse(responseCode="200",description="Até dois lados, sem contraparte; vazio para negócio alheio ou ainda não consumido.",
        content=@Content(schema=@Schema(implementation=CreditosDTO.class)))
    @APIResponse(responseCode="400",description="Identificador inválido.")
    @APIResponse(responseCode="401",description="Token ausente ou inválido.")
    @APIResponse(responseCode="403",description="Conta ou escopo não permitido.")
    public Response consultar(@PathParam("negocio") String negocio) {
        return Response.ok(actor.consultar(token.getSubject(),negocio)).header("Cache-Control","no-store").build();
    }
}
