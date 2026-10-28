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
    /** Somente contas de teste e de medição: a conta da tela (CONTA-001) e o robô (CONTA-004) nunca são redefinidos. */
    static final Set<String> PREPARAVEIS = Set.of("CONTA-002", "CONTA-003", "CONTA-005");
    private static final Logger LOG = Logger.getLogger(CarteiraActor.class);
    private final RepositorioRisco repositorio;
    private final boolean preparacaoHabilitada;
    private final boolean recusarLiquidacaoPendente;

    /**
     * {@code recusarLiquidacaoPendente} (MO_RECUSAR_LIQUIDACAO_PENDENTE) é ligada na RF08e. No perfil legado de testes D0, toda
     * negociação deixa crédito pendente e as planilhas repetidas da CI não conseguiriam preparar (ADR-016, RF08e liga).
     */
    @Inject
    public CarteiraActor(RepositorioRisco repositorio,
                         @ConfigProperty(name = "prospero.risco.preparacao.habilitada", defaultValue = "false")
                         boolean preparacaoHabilitada,
                         @ConfigProperty(name = "prospero.risco.preparacao.recusar-liquidacao-pendente",
                                 defaultValue = "true") boolean recusarLiquidacaoPendente) {
        this.repositorio = repositorio;
        this.preparacaoHabilitada = preparacaoHabilitada;
        this.recusarLiquidacaoPendente = recusarLiquidacaoPendente;
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
                    "Somente as contas de teste CONTA-002, CONTA-003 e CONTA-005 podem ser preparadas.");
        }
        BigDecimal saldo = EntradaCarteira.saldo(pedido.saldo());
        Map<String, Long> custodia = EntradaCarteira.custodia(pedido.custodia(), ticker ->
                repositorio.parametros(ticker).isPresent());
        // Lock da conta antes da leitura: crédito e expectativa novos esperam; a recusa precede qualquer escrita.
        repositorio.bloquearConta(conta);
        if (recusarLiquidacaoPendente && repositorio.liquidacaoPendente(conta)) {
            throw new RegraNegocioException(Categoria.CONFLITO, "LIQUIDACAO_PENDENTE",
                    "Conta possui liquidação pendente; avance a data de movimento antes de preparar.");
        }
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
