package br.prospero.fo.ordens.actor;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.ordens.dominio.OperacaoCancelamento;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.RepositorioCancelamento;
import br.prospero.fo.ordens.dto.CancelamentoResumoDTO;
import br.prospero.fo.ordens.dominio.RepositorioOrdens;
import br.prospero.fo.ordens.dto.OcoResponseDTO;
import br.prospero.fo.ordens.dto.OrdemResponseDTO;
import br.prospero.fo.ordens.dto.PaginaOrdensDTO;
import br.prospero.fo.ordens.helper.CursorOrdens;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Consulta restrita à conta do token; ordem de outra conta é indistinguível de inexistente (404). */
@ApplicationScoped
public class ConsultarOrdensActor {
    public static final int LIMITE_PADRAO = 50;
    public static final int LIMITE_MAXIMO = 100;
    private final RepositorioOrdens repositorio;
    private final RepositorioCancelamento cancelamentos;

    @Inject
    public ConsultarOrdensActor(RepositorioOrdens repositorio, RepositorioCancelamento cancelamentos) {
        this.repositorio = repositorio;
        this.cancelamentos = cancelamentos;
    }

    public OrdemResponseDTO buscar(String conta, UUID id) {
        exigirConta(conta);
        return repositorio.buscar(conta, id).map(o -> dto(o, cancelamentos.buscar(conta, id).orElse(null)))
                .orElseThrow(() -> naoEncontrado("ORDEM_NAO_ENCONTRADA"));
    }

    public OcoResponseDTO buscarOco(String conta, UUID grupo) {
        exigirConta(conta);
        List<Ordem> ordens = repositorio.buscarGrupoOco(conta, grupo);
        if (ordens.isEmpty()) {
            throw naoEncontrado("GRUPO_OCO_NAO_ENCONTRADO");
        }
        return oco(ordens);
    }

    public PaginaOrdensDTO listar(String conta, String cursor, Integer limite) {
        exigirConta(conta);
        int tamanho = limite == null ? LIMITE_PADRAO : limite;
        if (tamanho < 1 || tamanho > LIMITE_MAXIMO) {
            throw new RegraNegocioException(Categoria.ENTRADA_INVALIDA, "LIMITE_PAGINA_INVALIDO",
                    "Limite deve estar entre 1 e 100.");
        }
        List<Ordem> ordens = repositorio.listar(conta, CursorOrdens.decodificar(cursor), tamanho + 1);
        boolean haMais = ordens.size() > tamanho;
        List<Ordem> pagina = haMais ? ordens.subList(0, tamanho) : ordens;
        String proximo = haMais ? CursorOrdens.codificar(new RepositorioOrdens.Cursor(pagina.getLast().recebidaEm(),
                pagina.getLast().id())) : null;
        Map<UUID, OperacaoCancelamento> operacoes = cancelamentos.buscar(conta, pagina.stream().map(Ordem::id).toList());
        return new PaginaOrdensDTO(pagina.stream().map(o -> dto(o, operacoes.get(o.id()))).toList(), proximo);
    }

    public static OcoResponseDTO oco(List<Ordem> ordens) {
        return new OcoResponseDTO(ordens.getFirst().grupoOco(), ordens.stream().map(ConsultarOrdensActor::dto).toList());
    }

    public static OrdemResponseDTO dto(Ordem o) {
        return dto(o, null);
    }

    public static OrdemResponseDTO dto(Ordem o, OperacaoCancelamento cancelamento) {
        return new OrdemResponseDTO(o.id(), o.ticker(), o.lado().name(), o.tipo().name(), o.validade().name(),
                o.quantidade(), texto(o.precoLimite()), texto(o.precoGatilho()), o.grupoOco(),
                o.papelOco() == null ? null : o.papelOco().name(), o.estado().name(), o.dataSessao(), o.recebidaEm(),
                o.baseTunel() == null ? null : o.baseTunel().preco().toPlainString(),
                o.baseTunel() == null ? null : o.baseTunel().origem().name(),
                o.motivoRejeicao() == null ? null : o.motivoRejeicao().name(),
                o.quantidadeExecutada(), o.quantidadeCancelada(), o.motivoEncerramento(), o.quantidadeReduzida(),
                cancelamento == null ? null : CancelamentoResumoDTO.de(cancelamento));
    }

    private static String texto(BigDecimal valor) {
        return valor == null ? null : valor.toPlainString();
    }

    private void exigirConta(String conta) {
        if (conta == null || !repositorio.contaExiste(conta)) {
            throw new RegraNegocioException(Categoria.PROIBIDO, "CONTA_DESCONHECIDA", "Token sem conta cadastrada.");
        }
    }

    private static RegraNegocioException naoEncontrado(String codigo) {
        return new RegraNegocioException(Categoria.NAO_ENCONTRADO, codigo, "Recurso não encontrado para esta conta.");
    }
}
