package br.prospero.fo.ordens.jpa;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.kafka.AvisoOutbox;
import br.prospero.fo.comum.dominio.MensagemSaida;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.ordens.dominio.BaseTunel;
import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.MotivoRejeicao;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.OrigemBase;
import br.prospero.fo.ordens.dominio.PapelOco;
import br.prospero.fo.ordens.dominio.ParametrosAtivo;
import br.prospero.fo.ordens.dominio.RepositorioOrdens;
import br.prospero.fo.ordens.dominio.ResultadoRegistro;
import br.prospero.fo.ordens.dominio.ResultadoRisco;
import br.prospero.fo.ordens.dominio.TipoOrdem;
import br.prospero.fo.ordens.dominio.Validade;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.query.NativeQuery;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class RepositorioOrdensJpa implements RepositorioOrdens {
    static final Duration RETENCAO_CHAVE = Duration.ofHours(24);
    static final int MAX_ORDENS_SESSAO = 1000;
    private static final int EXPURGO_LOTE = 100;
    private final EntityManager em;
    @Inject
    RepositorioTrajetoriaJpa trajetoria;
    @Inject AvisoOutbox avisoOutbox;
    @Inject RepositorioCancelamentoJpa cancelamentos;

    @Inject
    public RepositorioOrdensJpa(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional
    public boolean contaExiste(String conta) {
        return !em.createNativeQuery("SELECT 1 FROM conta WHERE id = :c").setParameter("c", conta).getResultList().isEmpty();
    }

    @Override
    @Transactional
    public Optional<ParametrosAtivo> parametros(String ticker) {
        // Leitura própria do catálogo do FO, sem depender do adaptador JPA de mercado nem da projeção sintética.
        List<?> linhas = em.createNativeQuery("SELECT tick, lote, preco_referencia FROM ativo WHERE ticker = :t")
                .setParameter("t", ticker).getResultList();
        return linhas.stream().findFirst().map(linha -> {
            Object[] c = (Object[]) linha;
            return new ParametrosAtivo(ticker, (BigDecimal) c[0], ((Number) c[1]).longValue(), (BigDecimal) c[2]);
        });
    }

    @Override
    @Transactional
    public Optional<ResultadoRegistro> repeticao(String conta, String chave, String hash, Instant agora) {
        List<?> linhas = em.createNativeQuery("""
                        SELECT hash, referencia FROM idempotencia_envio
                        WHERE conta = :c AND chave = :k AND criado_em >= :valida""")
                .setParameter("c", conta).setParameter("k", chave)
                .setParameter("valida", OffsetDateTime.ofInstant(agora.minus(RETENCAO_CHAVE), ZoneOffset.UTC))
                .getResultList();
        return linhas.stream().findFirst().map(linha -> {
            Object[] existente = (Object[]) linha;
            return hash.equals(existente[0]) ? new ResultadoRegistro.Repetido(porEnvio(conta, (UUID) existente[1]))
                    : new ResultadoRegistro.Conflito();
        });
    }

    @Override
    @Transactional
    public ResultadoRegistro registrar(String conta, String chave, String hash, Instant agora, List<Ordem> ordens,
                                       MensagemSaida comandoRisco) {
        OffsetDateTime expirado = OffsetDateTime.ofInstant(agora.minus(RETENCAO_CHAVE), ZoneOffset.UTC);
        // SKIP LOCKED: envios concorrentes não esperam o expurgo uns dos outros.
        em.createNativeQuery("""
                        DELETE FROM idempotencia_envio WHERE ctid IN (
                            SELECT ctid FROM idempotencia_envio WHERE criado_em < :expirado LIMIT :lote FOR UPDATE SKIP LOCKED)""")
                .setParameter("expirado", expirado).setParameter("lote", EXPURGO_LOTE).executeUpdate();
        em.createNativeQuery("DELETE FROM idempotencia_envio WHERE conta = :c AND chave = :k AND criado_em < :expirado")
                .setParameter("c", conta).setParameter("k", chave).setParameter("expirado", expirado).executeUpdate();
        UUID referencia = ordens.getFirst().grupoEnvio();
        // Envio concorrente com a mesma chave espera o commit do primeiro e cai no ramo de repetição.
        boolean reservada = !em.createNativeQuery("""
                        INSERT INTO idempotencia_envio (conta, chave, hash, referencia, criado_em)
                        VALUES (:c, :k, :h, :r, :t) ON CONFLICT (conta, chave) DO NOTHING RETURNING referencia""")
                .setParameter("c", conta).setParameter("k", chave).setParameter("h", hash).setParameter("r", referencia)
                .setParameter("t", OffsetDateTime.ofInstant(agora, ZoneOffset.UTC)).getResultList().isEmpty();
        if (!reservada) {
            Object[] existente = (Object[]) em.createNativeQuery(
                            "SELECT hash, referencia FROM idempotencia_envio WHERE conta = :c AND chave = :k")
                    .setParameter("c", conta).setParameter("k", chave).getSingleResult();
            if (!hash.equals(existente[0])) {
                return new ResultadoRegistro.Conflito();
            }
            return new ResultadoRegistro.Repetido(porEnvio(conta, (UUID) existente[1]));
        }
        boolean dentroDoLimite = !em.createNativeQuery("""
                        INSERT INTO limite_ordens (conta, data_sessao, quantidade) VALUES (:c, :d, :n)
                        ON CONFLICT (conta, data_sessao) DO UPDATE SET quantidade = limite_ordens.quantidade + EXCLUDED.quantidade
                        WHERE limite_ordens.quantidade + EXCLUDED.quantidade <= :max RETURNING quantidade""")
                .setParameter("c", conta).setParameter("d", ordens.getFirst().dataSessao())
                .setParameter("n", ordens.size()).setParameter("max", MAX_ORDENS_SESSAO).getResultList().isEmpty();
        if (!dentroDoLimite) {
            // Exceção desfaz também a reserva da chave.
            throw new RegraNegocioException(Categoria.LIMITE_ATINGIDO, "LIMITE_ORDENS",
                    "Limite de 1000 ordens por conta na sessão atingido.");
        }
        ordens.forEach(o -> em.persist(entidade(o)));
        // Outbox na mesma transação (ADR-012): ordem sem comando de risco nunca confirma, e vice-versa.
        em.createNativeQuery("""
                        INSERT INTO outbox (id, topico, chave, tipo, payload, traceparent, criado_em)
                        VALUES (:id, :topico, :chave, :tipo, :payload, :trace, :criado)""")
                .unwrap(NativeQuery.class)
                .setParameter("id", comandoRisco.id()).setParameter("topico", comandoRisco.topico())
                .setParameter("chave", comandoRisco.chave()).setParameter("tipo", comandoRisco.tipo())
                .setParameter("payload", comandoRisco.payload())
                .setParameter("trace", comandoRisco.traceparent(), String.class)
                .setParameter("criado", OffsetDateTime.ofInstant(comandoRisco.criadaEm(), ZoneOffset.UTC))
                .executeUpdate();
        trajetoria.registrarRecebimento(ordens, comandoRisco);
        avisoOutbox.registrar();
        return new ResultadoRegistro.Novo(ordens);
    }

    @Override
    @Transactional
    public int aplicarRisco(ResultadoRisco r) {
        // Condicional: só ordens RECEBIDA da conta e intenção do resultado; repetição não altera nada.
        int alteradas = em.createNativeQuery("""
                        UPDATE ordem SET estado = :e, motivo_rejeicao = :m, versao = versao + 1
                        WHERE conta = :c AND grupo_envio = :g AND estado = 'RECEBIDA'""")
                .unwrap(NativeQuery.class)
                .setParameter("e", r.estado().name())
                .setParameter("m", r.motivo() == null ? null : r.motivo().name(), String.class)
                .setParameter("c", r.conta()).setParameter("g", r.grupoEnvio()).executeUpdate();
        var aguardando = cancelamentos.travarAguardandoRisco(r, alteradas);
        trajetoria.registrarRisco(r);
        cancelamentos.aposRisco(r, aguardando);
        return alteradas;
    }

    @Override
    @Transactional
    public Optional<Ordem> buscar(String conta, UUID id) {
        return em.createQuery("from OrdemEntity where id = :id and conta = :c", OrdemEntity.class)
                .setParameter("id", id).setParameter("c", conta).getResultStream().findFirst()
                .map(RepositorioOrdensJpa::ordem);
    }

    @Override
    @Transactional
    public List<Ordem> buscarGrupoOco(String conta, UUID grupoOco) {
        return em.createQuery("from OrdemEntity where grupoOco = :g and conta = :c order by papelOco desc", OrdemEntity.class)
                .setParameter("g", grupoOco).setParameter("c", conta).getResultList().stream()
                .map(RepositorioOrdensJpa::ordem).toList();
    }

    @Override
    @Transactional
    public List<Ordem> listar(String conta, Cursor cursor, int limite) {
        String filtro = cursor == null ? "" : " and (recebidaEm < :t or (recebidaEm = :t and id < :id))";
        var consulta = em.createQuery("from OrdemEntity where conta = :c" + filtro + " order by recebidaEm desc, id desc",
                OrdemEntity.class).setParameter("c", conta).setMaxResults(limite);
        if (cursor != null) {
            consulta.setParameter("t", cursor.recebidaEm()).setParameter("id", cursor.id());
        }
        return consulta.getResultList().stream().map(RepositorioOrdensJpa::ordem).toList();
    }

    private List<Ordem> porEnvio(String conta, UUID grupoEnvio) {
        return em.createQuery("from OrdemEntity where grupoEnvio = :g and conta = :c order by papelOco desc nulls first",
                        OrdemEntity.class).setParameter("g", grupoEnvio).setParameter("c", conta).getResultList().stream()
                .map(RepositorioOrdensJpa::ordem).toList();
    }

    private static OrdemEntity entidade(Ordem o) {
        OrdemEntity e = new OrdemEntity();
        e.id = o.id();
        e.conta = o.conta();
        e.ticker = o.ticker();
        e.lado = o.lado().name();
        e.tipo = o.tipo().name();
        e.validade = o.validade().name();
        e.quantidade = o.quantidade();
        e.precoLimite = o.precoLimite();
        e.precoGatilho = o.precoGatilho();
        e.grupoOco = o.grupoOco();
        e.papelOco = o.papelOco() == null ? null : o.papelOco().name();
        e.grupoEnvio = o.grupoEnvio();
        // RF05c.3b: as duas pernas OCO compartilham a admissão do grupo (unicidade por papel, V8).
        if (o.tipo() == TipoOrdem.LIMIT || o.tipo() == TipoOrdem.MARKET
                || o.tipo() == TipoOrdem.STOP || o.tipo() == TipoOrdem.STOP_LIMIT) {
            e.admissaoMatching = UUID.nameUUIDFromBytes(("admissao:" + o.grupoEnvio())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        e.estado = o.estado().name();
        e.dataSessao = o.dataSessao();
        e.recebidaEm = o.recebidaEm();
        if (o.baseTunel() != null) {
            e.precoBaseTunel = o.baseTunel().preco();
            e.origemBase = o.baseTunel().origem().name();
        }
        return e;
    }

    static Ordem ordem(OrdemEntity e) {
        return new Ordem(e.id, e.conta, e.ticker, LadoOrdem.valueOf(e.lado), TipoOrdem.valueOf(e.tipo),
                Validade.valueOf(e.validade), e.quantidade, e.precoLimite, e.precoGatilho, e.grupoOco,
                e.papelOco == null ? null : PapelOco.valueOf(e.papelOco), e.grupoEnvio, EstadoOrdem.valueOf(e.estado),
                e.dataSessao, e.recebidaEm,
                e.precoBaseTunel == null ? null : new BaseTunel(e.precoBaseTunel, OrigemBase.valueOf(e.origemBase)),
                e.motivoRejeicao == null ? null : MotivoRejeicao.valueOf(e.motivoRejeicao),
                e.quantidadeExecutada, e.quantidadeCancelada, e.motivoEncerramento, e.quantidadeReduzida);
    }
}
