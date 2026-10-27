package br.prospero.fo.ordens.actor;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.ordens.dominio.RepositorioOrdens;
import br.prospero.fo.ordens.dominio.RepositorioTrajetoria;
import br.prospero.fo.ordens.dto.PaginaTrajetoriaDTO;
import br.prospero.fo.ordens.dto.MarcoTrajetoriaDTO;
import br.prospero.fo.ordens.helper.CursorTrajetoria;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

@ApplicationScoped
public class ConsultarTrajetoriaActor {
    private final RepositorioOrdens ordens;
    private final RepositorioTrajetoria trajetoria;

    @Inject
    public ConsultarTrajetoriaActor(RepositorioOrdens ordens, RepositorioTrajetoria trajetoria) {
        this.ordens = ordens;
        this.trajetoria = trajetoria;
    }

    public PaginaTrajetoriaDTO consultar(String conta, UUID ordem, String cursor, Integer limite) {
        if (conta == null || !ordens.contaExiste(conta)) {
            throw new RegraNegocioException(Categoria.PROIBIDO, "CONTA_DESCONHECIDA", "Token sem conta cadastrada.");
        }
        int tamanho = limite == null ? 20 : limite;
        if (tamanho < 1 || tamanho > 100) {
            throw new RegraNegocioException(Categoria.ENTRADA_INVALIDA, "LIMITE_PAGINA_INVALIDO", "Limite deve estar entre 1 e 100.");
        }
        long apos = CursorTrajetoria.decodificar(cursor, conta, ordem);
        var pagina = trajetoria.consultar(conta, ordem, apos, tamanho + 1).orElseThrow(() ->
                new RegraNegocioException(Categoria.NAO_ENCONTRADO, "ORDEM_NAO_ENCONTRADA", "Recurso não encontrado para esta conta."));
        boolean mais = pagina.eventos().size() > tamanho;
        var eventos = mais ? pagina.eventos().subList(0, tamanho) : pagina.eventos();
        String proximo = mais ? CursorTrajetoria.codificar(conta, ordem, eventos.getLast().sequencia()) : null;
        return new PaginaTrajetoriaDTO(ordem, conta, Long.toString(pagina.revisao()), pagina.historicoCompleto(),
                pagina.disponivelDesde(), eventos.stream().map(MarcoTrajetoriaDTO::de).toList(), proximo);
    }
}
