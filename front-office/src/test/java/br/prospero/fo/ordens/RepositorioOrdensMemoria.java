package br.prospero.fo.ordens;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.RepositorioOrdens;
import br.prospero.fo.ordens.dominio.ResultadoRegistro;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Mesma semântica do adaptador JPA (chave 24 h, conflito por hash, limite por sessão) para os testes REST. */
@Alternative
@Priority(1)
@ApplicationScoped
public class RepositorioOrdensMemoria implements RepositorioOrdens {
    static final Set<String> CONTAS = Set.of("CONTA-001", "CONTA-002", "CONTA-003");
    private final List<Ordem> ordens = new ArrayList<>();
    private final Map<String, Object[]> chaves = new HashMap<>();
    private final Map<String, Integer> porSessao = new HashMap<>();
    private int maximoSessao = 1000;

    @Override
    public boolean contaExiste(String conta) {
        return CONTAS.contains(conta);
    }

    @Override
    public boolean ativoExiste(String ticker) {
        return Set.of("ALFA3", "BETA3", "GAMA3", "DELTA3", "OMEGA3").contains(ticker);
    }

    @Override
    public synchronized ResultadoRegistro registrar(String conta, String chave, String hash, Instant agora, List<Ordem> novas) {
        String id = conta + "|" + chave;
        Object[] existente = chaves.get(id);
        if (existente != null && ((Instant) existente[2]).isAfter(agora.minus(Duration.ofHours(24)))) {
            if (!existente[0].equals(hash)) {
                return new ResultadoRegistro.Conflito();
            }
            UUID referencia = (UUID) existente[1];
            return new ResultadoRegistro.Repetido(ordens.stream()
                    .filter(o -> o.conta().equals(conta) && o.grupoEnvio().equals(referencia)).toList());
        }
        String sessao = conta + "|" + novas.getFirst().dataSessao();
        int total = porSessao.getOrDefault(sessao, 0) + novas.size();
        if (total > maximoSessao) {
            throw new RegraNegocioException(Categoria.LIMITE_ATINGIDO, "LIMITE_ORDENS", "Limite atingido.");
        }
        porSessao.put(sessao, total);
        chaves.put(id, new Object[]{hash, novas.getFirst().grupoEnvio(), agora});
        ordens.addAll(novas);
        return new ResultadoRegistro.Novo(novas);
    }

    @Override
    public synchronized Optional<Ordem> buscar(String conta, UUID id) {
        return ordens.stream().filter(o -> o.conta().equals(conta) && o.id().equals(id)).findFirst();
    }

    @Override
    public synchronized List<Ordem> buscarGrupoOco(String conta, UUID grupoOco) {
        return ordens.stream().filter(o -> o.conta().equals(conta) && grupoOco.equals(o.grupoOco()))
                .sorted(Comparator.comparing(Ordem::papelOco).reversed()).toList();
    }

    @Override
    public synchronized List<Ordem> listar(String conta, Cursor cursor, int limite) {
        Comparator<Ordem> ordem = Comparator.comparing(Ordem::recebidaEm).thenComparing(Ordem::id).reversed();
        return ordens.stream().filter(o -> o.conta().equals(conta))
                .filter(o -> cursor == null || ordem.compare(o, chaveCursor(cursor)) > 0)
                .sorted(ordem).limit(limite).toList();
    }

    private static Ordem chaveCursor(Cursor cursor) {
        return new Ordem(cursor.id(), "x", "x", br.prospero.fo.ordens.dominio.LadoOrdem.COMPRA,
                br.prospero.fo.ordens.dominio.TipoOrdem.MARKET, br.prospero.fo.ordens.dominio.Validade.IOC, 1, null, null,
                null, null, cursor.id(), br.prospero.fo.ordens.dominio.EstadoOrdem.RECEBIDA, LocalDate.EPOCH,
                cursor.recebidaEm());
    }

    synchronized void maximoSessao(int maximo) {
        maximoSessao = maximo;
    }

    synchronized void limparTudo() {
        ordens.clear();
        chaves.clear();
        porSessao.clear();
        maximoSessao = 1000;
    }
}
