package br.prospero.fo.ordens.rest;

import br.prospero.fo.comum.dto.ProblemaDTO;
import br.prospero.fo.comum.rest.Problemas;
import br.prospero.fo.ordens.actor.ConsultarCancelamentoActor;
import br.prospero.fo.ordens.actor.SolicitarCancelamentoActor;
import br.prospero.fo.ordens.dto.CancelamentoResponseDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Cancelamento manual (RF10): operação canônica por ordem, solicitada por PUT e consultada por GET na mesma URI. */
@Path("/api/v1/ordens/{id}/cancelamento")
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@Tag(name = "Cancelamento", description = "Cancelamento manual de ordem da conta do token (RF10). 202 só confirma a "
        + "persistência do pedido; o saldo é liberado pelo middle-office depois do matching.")
public class OrdensCancelamentoResource {
    private final SolicitarCancelamentoActor solicitar;
    private final ConsultarCancelamentoActor consultar;
    private final JsonWebToken token;

    @Inject
    public OrdensCancelamentoResource(SolicitarCancelamentoActor solicitar, ConsultarCancelamentoActor consultar,
                                      JsonWebToken token) {
        this.solicitar = solicitar;
        this.consultar = consultar;
        this.token = token;
    }

    @PUT
    @RolesAllowed("ordens:enviar")
    @Operation(summary = "Solicitar cancelamento da ordem",
            description = "Corpo ausente ou {}. Repetir devolve a mesma operação sem novo pedido ao middle-office. "
                    + "Ordem sem decisão de risco fica AGUARDANDO_RISCO até a decisão.")
    @APIResponse(responseCode = "202", description = "Operação persistida (ou repetição).",
            content = @Content(schema = @Schema(implementation = CancelamentoResponseDTO.class)))
    @APIResponse(responseCode = "400", description = "Corpo diferente de ausente ou {}.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.")
    @APIResponse(responseCode = "403", description = "Sem escopo ordens:enviar ou conta desconhecida.")
    @APIResponse(responseCode = "404", description = "Ordem inexistente ou de outra conta.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "422", description = "Ordem de grupo OCO não é cancelável manualmente.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "503", description = "Emissão de cancelamentos desligada (CANCELAMENTO_INDISPONIVEL).",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    public Response solicitar(@PathParam("id") String id, @HeaderParam("Content-Type") String tipoConteudo,
                              String corpo) {
        CorpoCancelamento.exigirVazio(corpo, tipoConteudo);
        var resultado = solicitar.solicitar(token.getSubject(), RequisicaoOrdem.id(id, "ORDEM_NAO_ENCONTRADA"));
        return Response.accepted(CancelamentoResponseDTO.de(resultado.operacao()))
                .header("Cache-Control", "no-store").header("X-Cancelamento-Repetido", resultado.repetida()).build();
    }

    @GET
    @RolesAllowed("ordens:leitura")
    @Operation(summary = "Consultar a operação de cancelamento da ordem")
    @APIResponse(responseCode = "200", description = "Estado da operação.",
            content = @Content(schema = @Schema(implementation = CancelamentoResponseDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.")
    @APIResponse(responseCode = "403", description = "Sem escopo ordens:leitura.")
    @APIResponse(responseCode = "404", description = "Sem pedido, ordem inexistente ou de outra conta.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    public Response consultar(@PathParam("id") String id) {
        var operacao = consultar.buscar(token.getSubject(), RequisicaoOrdem.id(id, "CANCELAMENTO_NAO_ENCONTRADO"));
        return Response.ok(CancelamentoResponseDTO.de(operacao)).header("Cache-Control", "no-store").build();
    }
}
