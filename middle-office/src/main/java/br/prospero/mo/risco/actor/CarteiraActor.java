package br.prospero.mo.risco.actor;

import br.prospero.mo.comum.dominio.Categoria;
import br.prospero.mo.comum.dominio.RegraNegocioException;
import br.prospero.mo.risco.dominio.Carteira;
import br.prospero.mo.risco.dominio.RepositorioRisco;
import br.prospero.mo.risco.dto.CarteiraResponseDTO;
import br.prospero.mo.risco.dto.PreparacaoCarteiraRequestDTO;
import br.prospero.mo.risco.helper.EntradaCarteira;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** Consulta da carteira da própria conta e preparação local de carteiras de teste (RF04b, ADR-012). */
@ApplicationScoped
public class CarteiraActor {
    /** Somente contas de teste: a conta da tela (CONTA-001) nunca é redefinida. */
    static final Set<String> PREPARAVEIS = Set.of("CONTA-002", "CONTA-003");
    private static final Logger LOG = Logger.getLogger(CarteiraActor.class);
    private final RepositorioRisco repositorio;
    private final boolean preparacaoHabilitada;

    @Inject
    public CarteiraActor(RepositorioRisco repositorio,
                         @ConfigProperty(name = "prospero.risco.preparacao.habilitada", defaultValue = "false")
                         boolean preparacaoHabilitada) {
        this.repositorio = repositorio;
        this.preparacaoHabilitada = preparacaoHabilitada;
    }

    public CarteiraResponseDTO consultar(String conta) {
        return repositorio.carteira(exigirConta(conta)).map(CarteiraActor::dto).orElseThrow(() ->
                new RegraNegocioException(Categoria.PROIBIDO, "CONTA_DESCONHECIDA", "Token sem conta cadastrada."));
    }

    @Transactional
    public CarteiraResponseDTO preparar(String conta, PreparacaoCarteiraRequestDTO pedido) {
        if (!preparacaoHabilitada) {
            throw new RegraNegocioException(Categoria.NAO_ENCONTRADO, "PREPARACAO_DESABILITADA",
                    "Preparação de carteiras desabilitada neste ambiente.");
        }
        if (!PREPARAVEIS.contains(conta)) {
            throw new RegraNegocioException(Categoria.PROIBIDO, "CONTA_NAO_PREPARAVEL",
                    "Somente as contas de teste CONTA-002 e CONTA-003 podem ser preparadas.");
        }
        BigDecimal saldo = EntradaCarteira.saldo(pedido.saldo());
        Map<String, Long> custodia = EntradaCarteira.custodia(pedido.custodia(), ticker ->
                repositorio.parametros(ticker).isPresent());
        repositorio.preparar(conta, saldo, custodia);
        LOG.infof("Carteira de teste preparada: conta=%s", conta);
        return consultar(conta);
    }

    private static String exigirConta(String conta) {
        if (conta == null) {
            throw new RegraNegocioException(Categoria.PROIBIDO, "CONTA_DESCONHECIDA", "Token sem conta cadastrada.");
        }
        return conta;
    }

    private static CarteiraResponseDTO dto(Carteira c) {
        return new CarteiraResponseDTO(c.conta(), c.total().toPlainString(), c.disponivel().toPlainString(),
                c.reservado().toPlainString(), c.custodias().stream().map(x ->
                        new CarteiraResponseDTO.CustodiaResponseDTO(x.ticker(), x.total(), x.disponivel(),
                                x.reservado())).toList());
    }
}
