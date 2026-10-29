package br.prospero.mo.mensageria.actor;

import br.prospero.mo.comum.dominio.Categoria;
import br.prospero.mo.comum.dominio.RegraNegocioException;
import br.prospero.mo.mensageria.dominio.EventosRejeitados;
import br.prospero.mo.mensageria.dominio.ItemQuarentena;
import br.prospero.mo.mensageria.dominio.Reaplicacao;
import br.prospero.mo.mensageria.dominio.ResolucaoQuarentena;
import br.prospero.mo.mensageria.dto.QuarentenaItemDTO;
import br.prospero.mo.mensageria.dto.ResolucaoQuarentenaDTO;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Reprocessamento e descarte da quarentena (ADR-019). Sem transação envolvente: a reaplicação usa a transação do
 * caso de uso do consumidor, e o fechamento da linha é condicional, então chamadas repetidas ou concorrentes
 * terminam com uma única resolução.
 */
@ApplicationScoped
public class ReprocessarQuarentenaActor {
    private static final Logger LOG = Logger.getLogger(ReprocessarQuarentenaActor.class);
    private final EventosRejeitados quarentena;
    private final Reaplicacao reaplicacao;
    private final MeterRegistry metricas;
    /** Uma ação por linha de cada vez neste processo: reaplicar e descartar a mesma linha nunca se cruzam. */
    private final java.util.Set<Long> emAndamento = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject
    public ReprocessarQuarentenaActor(EventosRejeitados quarentena, Reaplicacao reaplicacao, MeterRegistry metricas) {
        this.quarentena = quarentena;
        this.reaplicacao = reaplicacao;
        this.metricas = metricas;
    }

    public List<QuarentenaItemDTO> pendentes(int limite) {
        if (limite < 1 || limite > 200) {
            throw new RegraNegocioException(Categoria.ENTRADA_INVALIDA, "LIMITE_INVALIDO", "Limite deve ficar entre 1 e 200.");
        }
        return quarentena.pendentes(limite).stream().map(QuarentenaItemDTO::de).toList();
    }

    public ResolucaoQuarentenaDTO reprocessar(long id, String autor) {
        return exclusivo(id, () -> reprocessarExclusivo(id, autor));
    }

    public ResolucaoQuarentenaDTO descartar(long id, String autor) {
        return exclusivo(id, () -> descartarExclusivo(id, autor));
    }

    private ResolucaoQuarentenaDTO exclusivo(long id, java.util.function.Supplier<ResolucaoQuarentenaDTO> acao) {
        if (!emAndamento.add(id)) {
            throw new RegraNegocioException(Categoria.CONFLITO, "QUARENTENA_EM_ANDAMENTO",
                    "Outra ação sobre esta mensagem está em andamento; repita depois.");
        }
        try {
            return acao.get();
        } finally {
            emAndamento.remove(id);
        }
    }

    private ResolucaoQuarentenaDTO reprocessarExclusivo(long id, String autor) {
        ItemQuarentena item = existente(id);
        if (!item.pendente()) return ResolucaoQuarentenaDTO.repetida(item);
        var mensagem = item.mensagem();
        if (mensagem.payload() == null) {
            throw new RegraNegocioException(Categoria.REGRA_VIOLADA, "QUARENTENA_SEM_PAYLOAD",
                    "Linha anterior à quarentena com payload; só pode ser descartada.");
        }
        if (!reaplicacao.suporta(mensagem.topico())) {
            throw new RegraNegocioException(Categoria.REGRA_VIOLADA, "TOPICO_SEM_REPROCESSADOR",
                    "Nenhum consumidor deste serviço reaplica o tópico da mensagem.");
        }
        try {
            reaplicacao.reaplicar(mensagem);
        } catch (RuntimeException erro) {
            String motivo = "reprocessamento: " + erro.getClass().getSimpleName();
            quarentena.falhou(id, motivo);
            metricas.counter("prospero.quarentena.reprocessamentos", "topico", mensagem.topico(), "resultado", "falha").increment();
            LOG.warnf("Reprocessamento falhou: id=%d topico=%s autor=%s erro=%s", id, mensagem.topico(), autor,
                    erro.getClass().getSimpleName());
            throw new RegraNegocioException(Categoria.CONFLITO, "REPROCESSAMENTO_FALHOU",
                    "A mensagem falhou de novo e continua em quarentena.");
        }
        boolean primeira = quarentena.resolver(id, ResolucaoQuarentena.REPROCESSADA);
        metricas.counter("prospero.quarentena.reprocessamentos", "topico", mensagem.topico(), "resultado", "ok").increment();
        LOG.infof("Quarentena reprocessada: id=%d topico=%s particao=%d offset=%d autor=%s", id, mensagem.topico(),
                mensagem.particao(), mensagem.deslocamento(), autor);
        return primeira ? ResolucaoQuarentenaDTO.nova(id, ResolucaoQuarentena.REPROCESSADA)
                : ResolucaoQuarentenaDTO.repetida(existente(id));
    }

    private ResolucaoQuarentenaDTO descartarExclusivo(long id, String autor) {
        ItemQuarentena item = existente(id);
        if (!item.pendente()) return ResolucaoQuarentenaDTO.repetida(item);
        boolean primeira = quarentena.resolver(id, ResolucaoQuarentena.DESCARTADA);
        LOG.infof("Quarentena descartada: id=%d topico=%s particao=%d offset=%d autor=%s", id, item.mensagem().topico(),
                item.mensagem().particao(), item.mensagem().deslocamento(), autor);
        return primeira ? ResolucaoQuarentenaDTO.nova(id, ResolucaoQuarentena.DESCARTADA)
                : ResolucaoQuarentenaDTO.repetida(existente(id));
    }

    private ItemQuarentena existente(long id) {
        return quarentena.buscar(id).orElseThrow(() -> new RegraNegocioException(Categoria.NAO_ENCONTRADO,
                "QUARENTENA_DESCONHECIDA", "Mensagem de quarentena desconhecida."));
    }
}
