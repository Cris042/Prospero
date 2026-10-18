package br.prospero.fo.ordens.rest;

import br.prospero.fo.comum.dto.ProblemaDTO;
import br.prospero.fo.comum.rest.Problemas;
import br.prospero.fo.ordens.actor.ConsultarOrdensActor;
import br.prospero.fo.ordens.actor.EnviarOrdemActor;
import br.prospero.fo.ordens.dto.OcoRequestDTO;
import br.prospero.fo.ordens.dto.OcoResponseDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;


@Path("/api/v1/ordens-oco")
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@Tag(name = "Ordens", description = "Envio e consulta de ordens da conta do token (RF02). Registro não implica execução.")
public class OrdensOcoResource {
    private final EnviarOrdemActor enviar;
    private final ConsultarOrdensActor consultar;
    private final JsonWebToken token;

    @Inject
    public OrdensOcoResource(EnviarOrdemActor enviar, ConsultarOrdensActor consultar, JsonWebToken token) {
        this.enviar = enviar;
        this.consultar = consultar;
        this.token = token;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ordens:enviar")
    @Operation(summary = "Registrar grupo OCO (Take Profit + Stop Loss)", description = "As duas pernas são gravadas atomicamente.")
    @Parameter(name = RequisicaoOrdem.CABECALHO_IDEMPOTENCIA, in = ParameterIn.HEADER, required = true,
            description = "Chave única por envio da conta (8–64 [A-Za-z0-9_-]); válida por 24 h.")
    @APIResponse(responseCode = "202", description = "Grupo registrado (ou repetição idempotente).",
            content = @Content(schema = @Schema(implementation = OcoResponseDTO.class)))
    @APIResponse(responseCode = "400", description = "JSON, chave de idempotência ou campos estruturais inválidos.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.")
    @APIResponse(responseCode = "403", description = "Sem escopo ordens:enviar ou conta desconhecida.")
    @APIResponse(responseCode = "409", description = "Chave já usada com outro pedido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "422", description = "Validade, tipo do Stop Loss, preços incoerentes ou ativo.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "429", description = "Limite de ordens da sessão atingido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    public Response enviar(@HeaderParam(RequisicaoOrdem.CABECALHO_IDEMPOTENCIA) String chave,
                           @NotNull @Valid OcoRequestDTO pedido) {
        EnviarOrdemActor.Envio envio = enviar.enviarOco(token.getSubject(), RequisicaoOrdem.chave(chave), pedido);
        OcoResponseDTO grupo = ConsultarOrdensActor.oco(envio.ordens());
        // Location relativo: atrás do proxy o host público difere do Host recebido.
        return Response.accepted(grupo).header("Location", "/api/v1/ordens-oco/" + grupo.grupoOco())
                .header("Cache-Control", "no-store").header("X-Idempotencia-Repetida", envio.repetido()).build();
    }

    @GET
    @Path("/{grupo}")
    @RolesAllowed("ordens:leitura")
    @Operation(summary = "Consultar grupo OCO da própria conta")
    @APIResponse(responseCode = "200", description = "Pernas do grupo.",
            content = @Content(schema = @Schema(implementation = OcoResponseDTO.class)))
    @APIResponse(responseCode = "404", description = "Inexistente ou de outra conta.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.")
    @APIResponse(responseCode = "403", description = "Sem escopo ordens:leitura.")
    public Response buscar(@PathParam("grupo") String grupo) {
        return Response.ok(consultar.buscarOco(token.getSubject(), RequisicaoOrdem.id(grupo, "GRUPO_OCO_NAO_ENCONTRADO")))
                .header("Cache-Control", "no-store").build();
    }
}
