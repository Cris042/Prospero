package br.prospero.mo.risco.jpa;

import br.prospero.mo.risco.dominio.Carteira;
import br.prospero.mo.risco.dominio.Decisao;
import br.prospero.mo.risco.dominio.Lado;
import br.prospero.mo.risco.dominio.MotivoRejeicao;
import br.prospero.mo.risco.dominio.ParametrosRisco;
import br.prospero.mo.risco.dominio.RepositorioRisco;
import br.prospero.mo.risco.dominio.ResultadoRisco;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.query.NativeQuery;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * SQL nativo: as reservas são UPDATE condicionais na linha de controle, sem ler-e-depois-gravar (HLD-003). Mutações
 * exigem a transação do Actor (MANDATORY): débito sem decisão e outbox nunca confirma sozinho.
 */
@ApplicationScoped
public class RepositorioRiscoJpa implements RepositorioRisco {
    private final EntityManager em;

    @Inject
    public RepositorioRiscoJpa(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional
    public Optional<Decisao> decisao(UUID intencao) {
        List<?> linhas = em.createNativeQuery("""
                        SELECT evento, conta, ticker, lado, resultado, motivo, valor_reservado, quantidade_reservada,
                               versao_parametros, decidida_em
                        FROM decisao_risco WHERE intencao = :i""").setParameter("i", intencao).getResultList();
        return linhas.stream().findFirst().map(linha -> {
            Object[] c = (Object[]) linha;
            return new Decisao(intencao, (UUID) c[0], (String) c[1], (String) c[2], Lado.valueOf((String) c[3]),
                    ResultadoRisco.valueOf((String) c[4]), c[5] == null ? null : MotivoRejeicao.valueOf((String) c[5]),
                    (BigDecimal) c[6], c[7] == null ? null : ((Number) c[7]).longValue(),
                    c[8] == null ? null : ((Number) c[8]).intValue(), instante(c[9]));
        });
    }

    @Override
    @Transactional
    public Optional<ParametrosRisco> parametros(String ticker) {
        List<?> linhas = em.createNativeQuery("""
                        SELECT lote, tick, tunel_percentual, quantidade_maxima, versao FROM ativo_risco WHERE ticker = :t""")
                .setParameter("t", ticker).getResultList();
        return linhas.stream().findFirst().map(linha -> {
            Object[] c = (Object[]) linha;
            return new ParametrosRisco(ticker, ((Number) c[0]).longValue(), (BigDecimal) c[1], (BigDecimal) c[2],
                    ((Number) c[3]).longValue(), ((Number) c[4]).intValue());
        });
    }

    @Override
    @Transactional
    public boolean contaExiste(String conta) {
        return !em.createNativeQuery("SELECT 1 FROM conta_risco WHERE conta = :c").setParameter("c", conta)
                .getResultList().isEmpty();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean reservarCaixa(String conta, BigDecimal valor) {
        // Suficiência e mutação no mesmo comando: a segunda transação concorrente reavalia o WHERE após o commit.
        return em.createNativeQuery("""
                        UPDATE conta_risco SET disponivel = disponivel - :v, reservado = reservado + :v
                        WHERE conta = :c AND disponivel >= :v""")
                .setParameter("v", valor).setParameter("c", conta).executeUpdate() == 1;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean reservarPosicaoCompra(String conta, String ticker, long quantidade) {
        // Limite e mutação no mesmo comando, como o caixa: duas compras concorrentes não passam juntas do limite.
        return em.createNativeQuery("""
                        UPDATE custodia_risco c SET compra_aberta = c.compra_aberta + :q
                        FROM ativo_risco a
                        WHERE c.conta = :c AND c.ticker = :t AND a.ticker = c.ticker
                          AND c.total + c.compra_aberta + :q <= COALESCE(c.limite_posicao, a.limite_posicao)""")
                .setParameter("q", quantidade).setParameter("c", conta).setParameter("t", ticker)
                .executeUpdate() == 1;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void liberarPosicaoCompra(String conta, String ticker, long quantidade) {
        int n = em.createNativeQuery("""
                        UPDATE custodia_risco SET compra_aberta = compra_aberta - :q
                        WHERE conta = :c AND ticker = :t AND compra_aberta >= :q""")
                .setParameter("q", quantidade).setParameter("c", conta).setParameter("t", ticker).executeUpdate();
        if (n != 1) throw new IllegalStateException("Posição reservada ausente.");
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean vendaADescobertoPermitida(String conta) {
        // A conta já está sob FOR UPDATE no actor: o limite lido não muda até o fim da decisão.
        return ((BigDecimal) em.createNativeQuery("SELECT limite_vendido FROM conta_risco WHERE conta = :c")
                .setParameter("c", conta).getSingleResult()).signum() > 0;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public OptionalLong reservarCustodiaComDescoberto(String conta, String ticker, long quantidade) {
        // Coberta = min(q, disponível), calculada e aplicada no mesmo comando; a parte a descoberto respeita o limite
        // de posição do lado vendido (ADR-020). Nenhuma linha: limite excedido, nada alterado.
        List<?> linhas = em.createNativeQuery("""
                        WITH alvo AS (SELECT conta, ticker, LEAST(CAST(:q AS bigint), disponivel) AS coberta
                                      FROM custodia_risco WHERE conta = :c AND ticker = :t FOR UPDATE)
                        UPDATE custodia_risco c SET disponivel = c.disponivel - alvo.coberta,
                            reservado = c.reservado + alvo.coberta,
                            descoberto_reservado = c.descoberto_reservado + (CAST(:q AS bigint) - alvo.coberta)
                        FROM alvo, ativo_risco a
                        WHERE c.conta = alvo.conta AND c.ticker = alvo.ticker AND a.ticker = c.ticker
                          AND (alvo.coberta = CAST(:q AS bigint) OR c.descoberto_reservado + c.descoberto_executado
                               + (CAST(:q AS bigint) - alvo.coberta) <= COALESCE(c.limite_posicao, a.limite_posicao))
                        RETURNING CAST(:q AS bigint) - alvo.coberta""")
                .setParameter("q", quantidade).setParameter("c", conta).setParameter("t", ticker).getResultList();
        return linhas.isEmpty() ? OptionalLong.empty() : OptionalLong.of(((Number) linhas.getFirst()).longValue());
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean reservarExposicao(String conta, String ticker, long descoberta) {
        return em.createNativeQuery("""
                        UPDATE conta_risco r SET exposicao_vendida = r.exposicao_vendida + CAST(:s AS bigint) * a.preco_referencia
                        FROM ativo_risco a
                        WHERE r.conta = :c AND a.ticker = :t
                          AND r.exposicao_vendida + CAST(:s AS bigint) * a.preco_referencia <= r.limite_vendido""")
                .setParameter("s", descoberta).setParameter("c", conta).setParameter("t", ticker).executeUpdate() == 1;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void desfazerExposicao(String conta, String ticker, long descoberta) {
        exigirUma(em.createNativeQuery("""
                        UPDATE conta_risco r SET exposicao_vendida = r.exposicao_vendida - CAST(:s AS bigint) * a.preco_referencia
                        FROM ativo_risco a WHERE r.conta = :c AND a.ticker = :t""")
                .setParameter("s", descoberta).setParameter("c", conta).setParameter("t", ticker).executeUpdate());
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean reservarCustodia(String conta, String ticker, long quantidade) {
        return em.createNativeQuery("""
                        UPDATE custodia_risco SET disponivel = disponivel - :q, reservado = reservado + :q
                        WHERE conta = :c AND ticker = :t AND disponivel >= :q""")
                .setParameter("q", quantidade).setParameter("c", conta).setParameter("t", ticker)
                .executeUpdate() == 1;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void desfazerCustodiaVenda(String conta, String ticker, long coberta, long descoberta) {
        exigirUma(em.createNativeQuery("""
                        UPDATE custodia_risco SET disponivel = disponivel + :cob, reservado = reservado - :cob,
                            descoberto_reservado = descoberto_reservado - :s
                        WHERE conta = :c AND ticker = :t AND reservado >= :cob AND descoberto_reservado >= :s""")
                .setParameter("cob", coberta).setParameter("s", descoberta).setParameter("c", conta)
                .setParameter("t", ticker).executeUpdate());
    }

    private static void exigirUma(int alteradas) {
        if (alteradas != 1) throw new IllegalStateException("Linha de controle de risco ausente.");
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void registrarDescoberto(UUID intencao, long descoberta, BigDecimal garantia) {
        exigirUma(em.createNativeQuery("""
                        UPDATE decisao_risco SET quantidade_descoberta = :s, garantia = :g
                        WHERE intencao = :i AND resultado = 'RESERVADA'""")
                .setParameter("s", descoberta).setParameter("g", garantia).setParameter("i", intencao).executeUpdate());
        exigirUma(em.createNativeQuery("""
                        UPDATE reserva SET descoberta_restante = :s, garantia_restante = :g
                        WHERE intencao = :i AND recurso = 'CUSTODIA'""")
                .setParameter("s", descoberta).setParameter("g", garantia).setParameter("i", intencao).executeUpdate());
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void registrar(Decisao d) {
        OffsetDateTime quando = OffsetDateTime.ofInstant(d.decididaEm(), ZoneOffset.UTC);
        // Intenção concorrente duplicada viola a PK e desfaz a transação inteira; a reentrega encontra a decisão.
        nativa("""
                        INSERT INTO decisao_risco (intencao, evento, conta, ticker, lado, resultado, motivo, valor_reservado,
                                                   quantidade_reservada, versao_parametros, decidida_em)
                        VALUES (:i, :e, :c, :t, :l, :r, :m, :v, :q, :p, :d)""")
                .setParameter("i", d.intencao()).setParameter("e", d.evento()).setParameter("c", d.conta())
                .setParameter("t", d.ticker()).setParameter("l", d.lado().name()).setParameter("r", d.resultado().name())
                .setParameter("m", d.motivo() == null ? null : d.motivo().name(), String.class)
                .setParameter("v", d.valorReservado(), BigDecimal.class)
                .setParameter("q", d.quantidadeReservada(), Long.class)
                .setParameter("p", d.versaoParametros(), Integer.class).setParameter("d", quando).executeUpdate();
        if (d.resultado() == ResultadoRisco.RESERVADA) {
            nativa("""
                            INSERT INTO reserva (intencao, conta, ticker, recurso, valor, quantidade, estado, criada_em)
                            VALUES (:i, :c, :t, :r, :v, :q, 'ATIVA', :d)""")
                    .setParameter("i", d.intencao()).setParameter("c", d.conta()).setParameter("t", d.ticker())
                    .setParameter("r", d.valorReservado() != null ? "CAIXA" : "CUSTODIA")
                    .setParameter("v", d.valorReservado(), BigDecimal.class)
                    .setParameter("q", d.quantidadeReservada(), Long.class)
                    .setParameter("d", quando).executeUpdate();
        }
    }

    @Override
    @Transactional
    public Optional<Carteira> carteira(String conta) {
        List<?> caixa = em.createNativeQuery("""
                        SELECT total, disponivel, reservado, exposicao_vendida, limite_vendido FROM conta_risco WHERE conta = :c""")
                .setParameter("c", conta).getResultList();
        if (caixa.isEmpty()) {
            return Optional.empty();
        }
        Object[] c = (Object[]) caixa.getFirst();
        List<Carteira.Custodia> custodias = em.createNativeQuery("""
                        SELECT ticker, total, disponivel, reservado, descoberto_reservado, descoberto_executado
                        FROM custodia_risco WHERE conta = :c ORDER BY ticker""")
                .setParameter("c", conta).getResultList().stream().map(linha -> {
                    Object[] l = (Object[]) linha;
                    return new Carteira.Custodia((String) l[0], ((Number) l[1]).longValue(),
                            ((Number) l[2]).longValue(), ((Number) l[3]).longValue(), ((Number) l[4]).longValue(),
                            ((Number) l[5]).longValue());
                }).toList();
        return Optional.of(new Carteira(conta, (BigDecimal) c[0], (BigDecimal) c[1], (BigDecimal) c[2], custodias,
                (BigDecimal) c[3], (BigDecimal) c[4]));
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void preparar(String conta, BigDecimal saldo, Map<String, Long> custodia) {
        bloquearConta(conta);
        if (!em.createNativeQuery("SELECT 1 FROM reserva WHERE conta = :c AND estado IN ('ATIVA', 'PARCIAL')")
                .setParameter("c", conta).getResultList().isEmpty()) {
            throw new br.prospero.mo.comum.dominio.RegraNegocioException(
                    br.prospero.mo.comum.dominio.Categoria.CONFLITO, "RESERVA_VIVA", "Conta possui reserva ativa.");
        }
        // Preparação redefine a carteira inteira: a garantia de posição vendida some junto com o reservado (ADR-021).
        em.createNativeQuery("""
                        UPDATE conta_risco SET total = :s, disponivel = :s, reservado = 0, exposicao_vendida = 0
                        WHERE conta = :c""")
                .setParameter("s", saldo).setParameter("c", conta).executeUpdate();
        List<?> tickers = em.createNativeQuery(
                        "SELECT ticker FROM custodia_risco WHERE conta = :c ORDER BY ticker FOR UPDATE")
                .setParameter("c", conta).getResultList();
        for (Object ticker : tickers) {
            long quantidade = custodia.getOrDefault((String) ticker, 0L);
            em.createNativeQuery("""
                            UPDATE custodia_risco SET total = :q, disponivel = :q, reservado = 0, compra_aberta = 0,
                                descoberto_reservado = 0, descoberto_executado = 0, garantia_executada = 0
                            WHERE conta = :c AND ticker = :t""")
                    .setParameter("q", quantidade).setParameter("c", conta).setParameter("t", ticker).executeUpdate();
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean liquidacaoPendente(String conta) {
        // Índice parcial liquidacao_expectativa_pendente: só expectativas não creditadas da conta.
        return !em.createNativeQuery("""
                        SELECT 1 FROM liquidacao_expectativa WHERE conta = :c AND creditada_em IS NULL LIMIT 1""")
                .setParameter("c", conta).getResultList().isEmpty();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void bloquearConta(String conta) {
        em.createNativeQuery("SELECT conta FROM conta_risco WHERE conta = :c FOR UPDATE")
                .setParameter("c", conta).getResultList();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void registrarAdmissao(br.prospero.mo.risco.dominio.ComandoRisco c, Decisao d) {
        if (c.oco()) {
            registrarAdmissaoOco(c, d);
            return;
        }
        var p = c.pernas().getFirst();
        nativa("""
                UPDATE reserva SET ordem=:o, admissao=:a, tipo=:t, validade=:v, data_sessao=:s,
                quantidade_inicial=:q, quantidade_restante=:q, preco_limite=:l, preco_maximo=:m,
                valor_restante=:r, preco_gatilho=:g WHERE intencao=:i
                """).setParameter("o", p.id()).setParameter("a", br.prospero.mo.risco.helper.EnvelopeAdmissao.id(c.intencao())).setParameter("t", p.tipo().name())
                .setParameter("v", p.validade().name()).setParameter("s", c.dataSessao())
                .setParameter("q", p.quantidade()).setParameter("l", p.precoLimite(), BigDecimal.class)
                .setParameter("m", d.valorReservado() == null ? null : d.valorReservado().divide(BigDecimal.valueOf(p.quantidade())), BigDecimal.class)
                .setParameter("r", d.valorReservado(), BigDecimal.class).setParameter("g", p.precoGatilho(), BigDecimal.class).setParameter("i", c.intencao()).executeUpdate();
    }

    /** Grupo e as duas pernas na mesma transação da decisão e da outbox (ADR-013 RF05c.3b). */
    private void registrarAdmissaoOco(br.prospero.mo.risco.dominio.ComandoRisco c, Decisao d) {
        long q = c.pernas().getFirst().quantidade();
        int n = nativa("""
                UPDATE reserva SET oco=TRUE, admissao=:a, validade='DIA', data_sessao=:s, quantidade_inicial=:q,
                quantidade_restante=:q, preco_maximo=:m, valor_restante=:r WHERE intencao=:i AND ordem IS NULL
                """).setParameter("a", br.prospero.mo.risco.helper.EnvelopeAdmissao.id(c.intencao()))
                .setParameter("s", c.dataSessao()).setParameter("q", q)
                .setParameter("m", br.prospero.mo.risco.helper.EnvelopeAdmissao.precoMaximo(c, d), BigDecimal.class)
                .setParameter("r", d.valorReservado(), BigDecimal.class).setParameter("i", c.intencao()).executeUpdate();
        if (n != 1) {
            throw new IllegalStateException("Reserva do par ausente.");
        }
        String[] papeis = {"TAKE_PROFIT", "STOP_LOSS"};
        for (int i = 0; i < 2; i++) {
            var p = c.pernas().get(i);
            nativa("""
                    INSERT INTO reserva_perna(ordem,intencao,papel,tipo,preco_limite,preco_gatilho,quantidade)
                    VALUES(:o,:i,:p,:t,:l,:g,:q)
                    """).setParameter("o", p.id()).setParameter("i", c.intencao()).setParameter("p", papeis[i])
                    .setParameter("t", p.tipo().name()).setParameter("l", p.precoLimite(), BigDecimal.class)
                    .setParameter("g", p.precoGatilho(), BigDecimal.class).setParameter("q", q).executeUpdate();
        }
    }

    /** Nulos tipados: sem o tipo, o PostgreSQL recebe o parâmetro nulo como bytea. */
    private NativeQuery<?> nativa(String sql) {
        return em.createNativeQuery(sql).unwrap(NativeQuery.class);
    }

    private static Instant instante(Object valor) {
        return switch (valor) {
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case java.sql.Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Tipo de data inesperado.");
        };
    }
}
