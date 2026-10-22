package br.prospero.fo.ordens;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.MensagemSaida;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.ParametrosAtivo;
import br.prospero.fo.ordens.dominio.RepositorioOrdens;
import br.prospero.fo.ordens.dominio.ResultadoRegistro;
import br.prospero.fo.ordens.dominio.ResultadoRisco;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.math.BigDecimal;
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
    static final Set<String> CONTAS = Set.of("CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004");
    private final List<Ordem> ordens = new ArrayList<>();
    private final List<MensagemSaida> outbox = new ArrayList<>();
    private final Map<String, Object[]> chaves = new HashMap<>();
    private final Map<String, Integer> porSessao = new HashMap<>();
    private int maximoSessao = 1000;

    @Override
    public boolean contaExiste(String conta) {
        return CONTAS.contains(conta);
    }

    static final Map<String, ParametrosAtivo> ATIVOS = Map.of(
            "ALFA3", new ParametrosAtivo("ALFA3", new BigDecimal("0.01"), 1, new BigDecimal("10.00")),
            "BETA3", new ParametrosAtivo("BETA3", new BigDecimal("0.01"), 1, new BigDecimal("20.00")),
            "GAMA3", new ParametrosAtivo("GAMA3", new BigDecimal("0.01"), 1, new BigDecimal("30.00")),
            "DELTA3", new ParametrosAtivo("DELTA3", new BigDecimal("0.01"), 1, new BigDecimal("40.00")),
            "OMEGA3", new ParametrosAtivo("OMEGA3", new BigDecimal("0.05"), 100, new BigDecimal("50.00")));

    @Override
    public Optional<ParametrosAtivo> parametros(String ticker) {
        return Optional.ofNullable(ATIVOS.get(ticker));
    }

    @Override
    public synchronized Optional<ResultadoRegistro> repeticao(String conta, String chave, String hash, Instant agora) {
        Object[] existente = chaves.get(conta + "|" + chave);
        if (existente == null || !((Instant) existente[2]).isAfter(agora.minus(Duration.ofHours(24)))) {
            return Optional.empty();
        }
        if (!existente[0].equals(hash)) {
            return Optional.of(new ResultadoRegistro.Conflito());
        }
        UUID referencia = (UUID) existente[1];
        return Optional.of(new ResultadoRegistro.Repetido(ordens.stream()
                .filter(o -> o.conta().equals(conta) && o.grupoEnvio().equals(referencia)).toList()));
    }

    @Override
    public synchronized ResultadoRegistro registrar(String conta, String chave, String hash, Instant agora, List<Ordem> novas,
                                                    MensagemSaida comandoRisco) {
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
        outbox.add(comandoRisco);
        return new ResultadoRegistro.Novo(novas);
    }

    @Override
    public synchronized int aplicarRisco(ResultadoRisco r) {
        int alteradas = 0;
        for (int i = 0; i < ordens.size(); i++) {
            Ordem o = ordens.get(i);
            if (o.conta().equals(r.conta()) && o.grupoEnvio().equals(r.grupoEnvio()) && o.estado() == EstadoOrdem.RECEBIDA) {
                ordens.set(i, new Ordem(o.id(), o.conta(), o.ticker(), o.lado(), o.tipo(), o.validade(), o.quantidade(),
                        o.precoLimite(), o.precoGatilho(), o.grupoOco(), o.papelOco(), o.grupoEnvio(), r.estado(),
                        o.dataSessao(), o.recebidaEm(), o.baseTunel(), r.motivo()));
                alteradas++;
            }
        }
        return alteradas;
    }

    synchronized List<MensagemSaida> outbox() {
        return List.copyOf(outbox);
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
                cursor.recebidaEm(), null);
    }

    synchronized void maximoSessao(int maximo) {
        maximoSessao = maximo;
    }

    synchronized void limparTudo() {
        ordens.clear();
        outbox.clear();
        chaves.clear();
        porSessao.clear();
        maximoSessao = 1000;
    }
}
