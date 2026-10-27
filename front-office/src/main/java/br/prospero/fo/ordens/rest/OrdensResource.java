package br.prospero.fo.ordens.rest;

import br.prospero.fo.comum.dto.ProblemaDTO;
import br.prospero.fo.comum.rest.Problemas;
import br.prospero.fo.ordens.actor.ConsultarOrdensActor;
import br.prospero.fo.ordens.actor.ConsultarTrajetoriaActor;
import br.prospero.fo.ordens.actor.EnviarOrdemActor;
import br.prospero.fo.ordens.dto.OrdemRequestDTO;
import br.prospero.fo.ordens.dto.OrdemResponseDTO;
import br.prospero.fo.ordens.dto.PaginaOrdensDTO;
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
import jakarta.ws.rs.QueryParam;
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


@Path("/api/v1/ordens")
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@Tag(name = "Ordens", description = "Envio e consulta de ordens da conta do token (RF02). Registro não implica execução.")
public class OrdensResource {
    private final EnviarOrdemActor enviar;
    private final ConsultarOrdensActor consultar;
    private final JsonWebToken token;
    private final ConsultarTrajetoriaActor trajetoria;

    @Inject
    public OrdensResource(EnviarOrdemActor enviar, ConsultarOrdensActor consultar, JsonWebToken token, ConsultarTrajetoriaActor trajetoria) {
        this.enviar = enviar;
        this.consultar = consultar;
        this.token = token;
        this.trajetoria = trajetoria;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ordens:enviar")
    @Operation(summary = "Registrar ordem", description = "202: processamento aceito e ordem RECEBIDA; repetir a mesma chave e corpo devolve a mesma ordem.")
    @Parameter(name = RequisicaoOrdem.CABECALHO_IDEMPOTENCIA, in = ParameterIn.HEADER, required = true,
            description = "Chave única por envio da conta (8–64 [A-Za-z0-9_-]); válida por 24 h.")
    @APIResponse(responseCode = "202", description = "Ordem registrada (ou repetição idempotente).",
            content = @Content(schema = @Schema(implementation = OrdemResponseDTO.class)))
    @APIResponse(responseCode = "400", description = "JSON, chave de idempotência ou campos estruturais inválidos.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.")
    @APIResponse(responseCode = "403", description = "Sem escopo ordens:enviar ou conta desconhecida.")
    @APIResponse(responseCode = "409", description = "Chave já usada com outro pedido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "415", description = "Content-Type diferente de application/json.")
    @APIResponse(responseCode = "422", description = "Combinação tipo × validade, campos por tipo, preço, quantidade ou ativo.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "429", description = "Limite de 1000 ordens por conta e sessão atingido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    public Response enviar(@HeaderParam(RequisicaoOrdem.CABECALHO_IDEMPOTENCIA) String chave,
                           @NotNull @Valid OrdemRequestDTO pedido) {
        EnviarOrdemActor.Envio envio = enviar.enviar(token.getSubject(), RequisicaoOrdem.chave(chave), pedido);
        OrdemResponseDTO ordem = ConsultarOrdensActor.dto(envio.ordens().getFirst());
        // Location relativo: atrás do proxy o host público difere do Host recebido.
        return Response.accepted(ordem).header("Location", "/api/v1/ordens/" + ordem.id())
                .header("Cache-Control", "no-store").header("X-Idempotencia-Repetida", envio.repetido()).build();
    }

    @GET
    @Path("/{id}")
    @RolesAllowed("ordens:leitura")
    @Operation(summary = "Consultar ordem da própria conta")
    @APIResponse(responseCode = "200", description = "Ordem e estado atual.",
            content = @Content(schema = @Schema(implementation = OrdemResponseDTO.class)))
    @APIResponse(responseCode = "404", description = "Inexistente ou de outra conta.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.")
    @APIResponse(responseCode = "403", description = "Sem escopo ordens:leitura.")
    public Response buscar(@PathParam("id") String id) {
        return Response.ok(consultar.buscar(token.getSubject(), RequisicaoOrdem.id(id, "ORDEM_NAO_ENCONTRADA")))
                .header("Cache-Control", "no-store").build();
    }

    @GET
    @Path("/{id}/trajetoria")
    @RolesAllowed("ordens:leitura")
    @Operation(summary = "Consultar trajetória durável da própria ordem", description = "Fatos observados em ordem de confirmação local; legado pode ter histórico incompleto.")
    @APIResponse(responseCode = "200", description = "Trajetória paginada.",
            content = @Content(schema = @Schema(implementation = br.prospero.fo.ordens.dto.PaginaTrajetoriaDTO.class)))
    @APIResponse(responseCode = "400", description = "Cursor ou limite inválido.")
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.")
    @APIResponse(responseCode = "403", description = "Sem escopo ou conta conhecida.")
    @APIResponse(responseCode = "404", description = "Ordem inexistente ou de outra conta.")
    @APIResponse(responseCode = "503", description = "Projeção em quarentena.")
    public Response trajetoria(@PathParam("id") String id, @QueryParam("cursor") String cursor,
                               @QueryParam("limite") String limite) {
        return Response.ok(trajetoria.consultar(token.getSubject(), RequisicaoOrdem.id(id, "ORDEM_NAO_ENCONTRADA"),
                cursor, RequisicaoOrdem.limite(limite))).header("Cache-Control", "no-store").build();
    }

    @GET
    @RolesAllowed("ordens:leitura")
    @Operation(summary = "Listar ordens da própria conta", description = "Mais recentes primeiro; paginação por cursor opaco.")
    @APIResponse(responseCode = "200", description = "Página de ordens.",
            content = @Content(schema = @Schema(implementation = PaginaOrdensDTO.class)))
    @APIResponse(responseCode = "400", description = "Cursor ou limite inválido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.")
    @APIResponse(responseCode = "403", description = "Sem escopo ordens:leitura.")
    public Response listar(@Parameter(description = "Cursor opaco retornado em proximoCursor.") @QueryParam("cursor") String cursor,
                           @Parameter(description = "1 a 100; padrão 50.") @QueryParam("limite") String limite) {
        return Response.ok(consultar.listar(token.getSubject(), cursor, RequisicaoOrdem.limite(limite)))
                .header("Cache-Control", "no-store").build();
    }
}
