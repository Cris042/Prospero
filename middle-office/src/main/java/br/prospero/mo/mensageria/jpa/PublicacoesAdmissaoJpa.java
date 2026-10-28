package br.prospero.mo.mensageria.jpa;

import br.prospero.mo.mensageria.dominio.MensagemSaida;
import jakarta.persistence.EntityManager;
import org.hibernate.query.NativeQuery;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Recibo técnico da admissão; as operações participam da transação do gravador/publicador. */
public final class PublicacoesAdmissaoJpa {
    private static final String TOPICO = "matching.comandos.v1";
    private static final String TIPO = "ORDEM_ADMITIDA";
    private final EntityManager em;

    public PublicacoesAdmissaoJpa(EntityManager em) {
        this.em = Objects.requireNonNull(em);
    }

    /** Snapshot da mensagem efetivamente confirmada pelo produtor, sem normalização de JSON ou headers. */
    public record Confirmada(UUID id, String topico, String chave, String tipo, String payload, String traceparent) {
        public Confirmada {
            Objects.requireNonNull(id);
            Objects.requireNonNull(topico);
            Objects.requireNonNull(chave);
            Objects.requireNonNull(tipo);
            Objects.requireNonNull(payload);
        }
    }

    public void registrar(MensagemSaida mensagem) {
        if (!TIPO.equals(mensagem.tipo())) return;
        var dados = new Confirmada(mensagem.id(), mensagem.topico(), mensagem.chave(), mensagem.tipo(),
                mensagem.payload(), mensagem.traceparent());
        validar(dados);
        var criada = OffsetDateTime.ofInstant(mensagem.criadaEm(), ZoneOffset.UTC);
        int novas = consulta("""
                INSERT INTO admissao_publicacao (admissao,topico,tipo,chave,payload,traceparent,criada_em)
                SELECT r.admissao,:topico,:tipo,:chave,:payload,:trace,:criada
                FROM reserva r WHERE r.admissao=:id AND r.ticker=:chave
                ON CONFLICT (admissao) DO NOTHING
                """, dados).setParameter("criada", criada).executeUpdate();
        if (novas == 1) return;
        long identicas = ((Number) consulta("""
                SELECT count(*) FROM admissao_publicacao a JOIN reserva r ON r.admissao=a.admissao
                WHERE a.admissao=:id AND a.topico=:topico AND a.tipo=:tipo AND a.chave=:chave
                AND r.ticker=:chave AND convert_to(a.payload,'UTF8')=convert_to(:payload,'UTF8')
                AND a.traceparent IS NOT DISTINCT FROM :trace AND a.criada_em=:criada
                """, dados).setParameter("criada", criada).getSingleResult()).longValue();
        if (identicas != 1) throw inconsistente();
    }

    public void confirmar(List<Confirmada> confirmadas) {
        if (confirmadas.isEmpty()) return;
        List<UUID> ids = confirmadas.stream().map(Confirmada::id).toList();
        Set<UUID> conhecidas = new HashSet<>();
        for (Object id : em.createNativeQuery("SELECT admissao FROM admissao_publicacao WHERE admissao IN (:ids)")
                .setParameter("ids", ids).getResultList()) {
            conhecidas.add((UUID) id);
        }
        for (Confirmada mensagem : confirmadas) {
            // Um recibo conhecido também detecta adulteração do tipo para outro tipo da outbox.
            if (!TIPO.equals(mensagem.tipo()) && !conhecidas.contains(mensagem.id())) continue;
            validar(mensagem);
            int alteradas = consulta("""
                    UPDATE admissao_publicacao a SET confirmada_em=COALESCE(a.confirmada_em,o.publicado_em)
                    FROM outbox o WHERE a.admissao=:id AND o.id=a.admissao AND o.publicado_em IS NOT NULL
                    AND a.topico=:topico AND o.topico=:topico AND a.tipo=:tipo AND o.tipo=:tipo
                    AND a.chave=:chave AND o.chave=:chave
                    AND convert_to(a.payload,'UTF8')=convert_to(:payload,'UTF8')
                    AND convert_to(o.payload,'UTF8')=convert_to(:payload,'UTF8')
                    AND a.traceparent IS NOT DISTINCT FROM :trace
                    AND o.traceparent IS NOT DISTINCT FROM :trace
                    """, mensagem).executeUpdate();
            if (alteradas != 1) throw inconsistente();
        }
    }

    private NativeQuery<?> consulta(String sql, Confirmada mensagem) {
        return em.createNativeQuery(sql).unwrap(NativeQuery.class)
                .setParameter("id", mensagem.id()).setParameter("topico", mensagem.topico())
                .setParameter("tipo", mensagem.tipo()).setParameter("chave", mensagem.chave())
                .setParameter("payload", mensagem.payload()).setParameter("trace", mensagem.traceparent(), String.class);
    }

    private static void validar(Confirmada mensagem) {
        int bytes = mensagem.payload().getBytes(StandardCharsets.UTF_8).length;
        String trace = mensagem.traceparent();
        if (!TOPICO.equals(mensagem.topico()) || !TIPO.equals(mensagem.tipo()) || bytes == 0 || bytes > 16384
                || (trace != null && (!trace.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                || trace.substring(3, 35).equals("0".repeat(32)) || trace.substring(36, 52).equals("0".repeat(16))))) {
            throw inconsistente();
        }
    }

    private static IllegalStateException inconsistente() {
        return new IllegalStateException("Publicação de admissão sem comprovante correspondente.");
    }
}
