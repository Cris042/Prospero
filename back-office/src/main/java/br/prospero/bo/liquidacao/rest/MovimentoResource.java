package br.prospero.bo.liquidacao.rest;

import br.prospero.bo.liquidacao.actor.MovimentoActor;
import br.prospero.bo.liquidacao.dominio.MovimentoInvalidoException;
import br.prospero.bo.liquidacao.dto.MovimentoResultadoDTO;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import java.time.LocalDate;

@Path("/api/v1/data-movimento")
@Produces("application/json")
@Consumes("application/json")
public class MovimentoResource {
    public record Pedido(String dataAlvo) { }
    @Inject MovimentoActor actor;
    @Inject SecurityIdentity identidade;
    @Inject JsonWebToken token;
    @ConfigProperty(name="prospero.liquidacao.relogio-habilitado", defaultValue="false") boolean habilitado;

    // Sem @RolesAllowed: a flag precisa responder 404 antes de autenticar (auth proativa desligada).
    @POST
    @SecurityRequirement(name="jwt")
    @Operation(summary="Avançar data de movimento local", description="Escopo liquidacao:administrar, sub e jti obrigatórios. Até 10 dias úteis; liquidação em lotes retomáveis de 50 lados.")
    @APIResponse(responseCode="200", description="Data persistida; liquidacoes conta apenas o lote síncrono, não os lotes posteriores.",
            content=@Content(schema=@Schema(implementation=MovimentoResultadoDTO.class)))
    @APIResponse(responseCode="400", description="Data inválida.")
    @APIResponse(responseCode="401", description="Token ausente ou inválido.")
    @APIResponse(responseCode="403", description="Escopo, sujeito ou jti inválido.")
    @APIResponse(responseCode="404", description="Relógio desabilitado.")
    @APIResponse(responseCode="409", description="Data retroativa.")
    @APIResponse(responseCode="422", description="Avanço maior que 10 dias úteis ou fora do calendário.")
    @APIResponse(responseCode="503", description="Projeção ou liquidação indisponível.")
    public Response avancar(Pedido pedido) {
        if (!habilitado) throw new NotFoundException();
        if (identidade.isAnonymous()) throw new io.quarkus.security.UnauthorizedException();
        if (!identidade.hasRole("liquidacao:administrar")) throw new io.quarkus.security.ForbiddenException();
        LocalDate data;
        try {
            data = LocalDate.parse(pedido.dataAlvo());
            if (!data.toString().equals(pedido.dataAlvo())) throw new IllegalArgumentException();
        } catch (RuntimeException erro) { throw new MovimentoInvalidoException(400,"DATA_INVALIDA"); }
        return Response.ok(actor.avancar(data, token.getSubject(), token.getTokenID())).header("Cache-Control","no-store").build();
    }
}
