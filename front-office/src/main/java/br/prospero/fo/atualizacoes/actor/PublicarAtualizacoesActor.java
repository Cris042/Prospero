package br.prospero.fo.atualizacoes.actor;

import br.prospero.fo.atualizacoes.dominio.ControleSessoes;
import br.prospero.fo.atualizacoes.jpa.RepositorioRevisoesJpa;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Avisos confirmados, sem estado financeiro ou fila por cliente. Uma consulta compartilhada por segundo. */
@ApplicationScoped
public class PublicarAtualizacoesActor {
    public record Acesso(String id, String conta, long expiraEm) { }
    public static final UserData.TypedKey<Acesso> ACESSO = new UserData.TypedKey<>("atualizacoes-acesso");
    private static final Logger LOG = Logger.getLogger(PublicarAtualizacoesActor.class);
    private final ControleSessoes controle = new ControleSessoes();
    private final Map<String, Sessao> sessoes = new ConcurrentHashMap<>();
    private final RepositorioRevisoesJpa repositorio;
    private final boolean consultarBanco;
    private final MeterRegistry metricas;
    private volatile Map<String, Long> revisoes = Map.of();
    private ScheduledThreadPoolExecutor agenda;
    private ScheduledThreadPoolExecutor consultas;
    private record Sessao(String conta, WebSocketConnection conexao, AtomicBoolean enviando,
                          AtomicLong enviada, AtomicBoolean inicial, AtomicReference<ScheduledFuture<?>> vencimento) { }

    @Inject
    public PublicarAtualizacoesActor(RepositorioRevisoesJpa repositorio, MeterRegistry metricas,
            @ConfigProperty(name = "prospero.atualizacoes.consulta-habilitada", defaultValue = "true") boolean consultarBanco) {
        this.repositorio = repositorio;
        this.consultarBanco = consultarBanco;
        this.metricas = metricas;
        metricas.gauge("prospero.atualizacoes.conexoes", sessoes, Map::size);
    }

    void iniciar(@Observes StartupEvent evento) {
        agenda = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("atualizacoes-revisoes").daemon().factory());
        agenda.setRemoveOnCancelPolicy(true);
        agenda.scheduleWithFixedDelay(() -> controle.expurgar(Instant.now().getEpochSecond())
                .forEach(id -> encerrar(id, new CloseReason(1008))), 250, 250, TimeUnit.MILLISECONDS);
        consultas = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("atualizacoes-consulta").daemon().factory());
        consultas.scheduleWithFixedDelay(this::ciclo, 1, 1, TimeUnit.SECONDS);
    }

    void parar(@Observes ShutdownEvent evento) {
        sessoes.keySet().forEach(id -> encerrar(id, CloseReason.NORMAL));
        if (agenda != null) agenda.shutdownNow();
        if (consultas != null) consultas.shutdownNow();
    }

    /** Reserva curta cobre upgrades abandonados; o endpoint confirma o prazo JWT ao abrir. */
    public Acesso reservar(String conta, long expiraEm) {
        long agora = Instant.now().getEpochSecond();
        String id = UUID.randomUUID().toString();
        if (expiraEm <= agora || !controle.reservar(conta, id, Math.min(expiraEm, agora + 10))) return null;
        return new Acesso(id, conta, expiraEm);
    }

    public void abrir(WebSocketConnection conexao) {
        Acesso acesso = conexao.userData().get(ACESSO);
        if (acesso == null || acesso.expiraEm() <= Instant.now().getEpochSecond()
                || !controle.confirmar(acesso.id(), acesso.expiraEm())) {
            conexao.close(new CloseReason(1008)).subscribe().with(v -> { }, erro -> { });
            return;
        }
        Sessao sessao = new Sessao(acesso.conta(), conexao, new AtomicBoolean(), new AtomicLong(-1),
                new AtomicBoolean(true), new AtomicReference<>());
        sessoes.put(acesso.id(), sessao);
        long espera = Math.max(0, TimeUnit.SECONDS.toMillis(acesso.expiraEm()) - System.currentTimeMillis());
        ScheduledFuture<?> fim = agenda.schedule(() -> encerrar(acesso.id(), new CloseReason(1008)), espera, TimeUnit.MILLISECONDS);
        sessao.vencimento().set(fim);
        if (sessoes.get(acesso.id()) != sessao) fim.cancel(false);
        enviar(sessao, revisoes.getOrDefault(acesso.conta(), 0L));
    }

    public void fechar(WebSocketConnection conexao) {
        Acesso acesso = conexao.userData().get(ACESSO);
        if (acesso != null) remover(acesso.id());
    }

    private void remover(String id) {
        Sessao sessao = sessoes.remove(id);
        controle.liberar(id);
        if (sessao != null && sessao.vencimento().get() != null) sessao.vencimento().get().cancel(false);
    }

    private void encerrar(String id, CloseReason motivo) {
        Sessao sessao = sessoes.get(id);
        remover(id);
        if (sessao != null) sessao.conexao().close(motivo).subscribe().with(v -> { }, erro -> { });
    }

    private void ciclo() {
        try {
            if (consultarBanco && !sessoes.isEmpty()) revisoes = repositorio.consultar();
            sessoes.values().forEach(sessao -> enviar(sessao, revisoes.getOrDefault(sessao.conta(), 0L)));
        } catch (RuntimeException erro) {
            LOG.warnf("Atualizações aguardam recuperação: %s", erro.getClass().getSimpleName());
        }
    }

    private void enviar(Sessao sessao, long revisao) {
        Acesso acesso = sessao.conexao().userData().get(ACESSO);
        if (acesso == null) return;
        if (acesso.expiraEm() <= Instant.now().getEpochSecond()) {
            encerrar(acesso.id(), new CloseReason(1008));
            return;
        }
        if (revisao <= sessao.enviada().get() || !sessao.conexao().isOpen()
                || !sessao.enviando().compareAndSet(false, true)) return;
        String tipo = sessao.inicial().get() ? "RESSINCRONIZAR" : "ATUALIZACAO";
        String frame = "{\"versao\":1,\"tipo\":\"" + tipo + "\",\"revisao\":\"" + revisao + "\"}";
        sessao.conexao().sendText(frame).ifNoItem().after(Duration.ofSeconds(3)).fail()
                .subscribe().with(v -> {
                    metricas.counter("prospero.atualizacoes.enviados", "tipo", tipo).increment();
                    sessao.enviada().set(revisao);
                    sessao.inicial().set(false);
                    sessao.enviando().set(false);
                }, erro -> {
                    metricas.counter("prospero.atualizacoes.envio_falhou").increment();
                    encerrar(acesso.id(), new CloseReason(1013));
                });
    }
}
