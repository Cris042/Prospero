package br.prospero.fo.mercado.actor;

import br.prospero.fo.mercado.dominio.Ativo;
import br.prospero.fo.mercado.dominio.Negocio;
import br.prospero.fo.mercado.dominio.Oferta;
import br.prospero.fo.mercado.dominio.RegraMercadoException;
import br.prospero.fo.mercado.dominio.RepositorioMercado;
import br.prospero.fo.mercado.dto.PreparacaoResponseDTO;
import br.prospero.fo.mercado.dto.SnapshotRequestDTO;
import br.prospero.fo.mercado.helper.ConversorDecimal;
import br.prospero.fo.mercado.helper.ValidadorSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Preparação sintética local: valida integralmente antes de substituir o snapshot. */
@ApplicationScoped
public class PrepararMercadoActor {
    private static final ZoneId SESSAO = ZoneId.of("America/Sao_Paulo");
    private final RepositorioMercado repositorio;
    private final boolean habilitada;
    private final long maxNamespaces;

    @Inject
    public PrepararMercadoActor(RepositorioMercado repositorio,
                                @ConfigProperty(name = "prospero.mercado.preparacao.habilitada", defaultValue = "false") boolean habilitada,
                                @ConfigProperty(name = "prospero.mercado.max-namespaces", defaultValue = "50") long maxNamespaces) {
        this.repositorio = repositorio;
        this.habilitada = habilitada;
        this.maxNamespaces = maxNamespaces;
    }

    public boolean habilitada() {
        return habilitada;
    }

    public PreparacaoResponseDTO preparar(UUID namespace, String ticker, SnapshotRequestDTO pedido) {
        Ativo ativo = repositorio.buscarAtivo(ticker)
                .orElseThrow(() -> new RegraMercadoException("ATIVO_DESCONHECIDO", "Ativo não cadastrado."));
        if (pedido.ofertas().size() > 2 * ValidadorSnapshot.MAX_OFERTAS_POR_LADO
                || pedido.negocios().size() > ValidadorSnapshot.MAX_NEGOCIOS) {
            throw new RegraMercadoException("LIMITE_EXCEDIDO", "Snapshot excede o número máximo de ofertas ou negócios.");
        }
        Instant agora = Instant.now();
        LocalDate hoje = LocalDate.ofInstant(agora, SESSAO);
        if (pedido.sessao().isAfter(hoje)) {
            throw new RegraMercadoException("SESSAO_FUTURA", "Sessão não pode ser posterior à data atual de São Paulo.");
        }
        List<Oferta> ofertas = pedido.ofertas().stream().map(o -> new Oferta(o.lado(),
                ConversorDecimal.preco(o.preco(), ativo.tick()), ValidadorSnapshot.quantidade(o.quantidade(), ativo))).toList();
        List<Negocio> negocios = pedido.negocios().stream().map(n -> new Negocio(
                ConversorDecimal.preco(n.preco(), ativo.tick()), ValidadorSnapshot.quantidade(n.quantidade(), ativo))).toList();
        ValidadorSnapshot.validar(ofertas, negocios);
        if (!repositorio.namespaceExiste(namespace) && repositorio.contarNamespaces() >= maxNamespaces) {
            throw new RegraMercadoException("LIMITE_NAMESPACES", "Número máximo de namespaces preparados atingido.");
        }
        long revisao = repositorio.substituir(namespace, ativo.ticker(), pedido.sessao(), agora, ofertas, negocios);
        return new PreparacaoResponseDTO(namespace, ativo.ticker(), revisao, agora);
    }

    public void limpar(UUID namespace) {
        repositorio.remover(namespace);
    }
}
