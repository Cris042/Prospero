package br.prospero.mo.liquidacao.actor;

import br.prospero.mo.comum.dominio.Categoria;
import br.prospero.mo.comum.dominio.RegraNegocioException;
import br.prospero.mo.liquidacao.dto.CreditosDTO;
import br.prospero.mo.liquidacao.jpa.RepositorioLiquidacaoJpa;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

@ApplicationScoped
public class ConsultarCreditoActor {
    @Inject RepositorioLiquidacaoJpa repositorio;
    private static final Set<String> CONTAS = Set.of("CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004", "CONTA-005");

    public CreditosDTO consultar(String conta, String negocio) {
        if (conta == null || !CONTAS.contains(conta)) {
            throw new RegraNegocioException(Categoria.PROIBIDO, "ACESSO_NEGADO", "Conta não permitida.");
        }
        if (negocio == null || !negocio.matches(
                "(ALFA3|BETA3|GAMA3|DELTA3|OMEGA3)-[0-9]{4}-[0-9]{2}-[0-9]{2}-[1-9][0-9]{0,17}")) {
            throw new RegraNegocioException(Categoria.ENTRADA_INVALIDA, "CONSULTA_INVALIDA", "Negócio inválido.");
        }
        return new CreditosDTO(conta, repositorio.consultarCreditos(conta, negocio));
    }
}
