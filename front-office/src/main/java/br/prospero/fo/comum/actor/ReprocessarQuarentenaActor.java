package br.prospero.fo.comum.actor;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.comum.dominio.EventosRejeitados;
import br.prospero.fo.comum.dominio.ItemQuarentena;
import br.prospero.fo.comum.dominio.Reaplicacao;
import br.prospero.fo.comum.dominio.ResolucaoQuarentena;
import br.prospero.fo.comum.dto.QuarentenaItemDTO;
import br.prospero.fo.comum.dto.ResolucaoQuarentenaDTO;
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

    public ResolucaoQuarentenaDTO reprocessar(long id) {
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
            LOG.warnf("Reprocessamento falhou: id=%d topico=%s erro=%s", id, mensagem.topico(), erro.getClass().getSimpleName());
            throw new RegraNegocioException(Categoria.CONFLITO, "REPROCESSAMENTO_FALHOU",
                    "A mensagem falhou de novo e continua em quarentena.");
        }
        boolean primeira = quarentena.resolver(id, ResolucaoQuarentena.REPROCESSADA);
        metricas.counter("prospero.quarentena.reprocessamentos", "topico", mensagem.topico(), "resultado", "ok").increment();
        LOG.infof("Quarentena reprocessada: id=%d topico=%s particao=%d offset=%d", id, mensagem.topico(),
                mensagem.particao(), mensagem.deslocamento());
        return primeira ? ResolucaoQuarentenaDTO.nova(id, ResolucaoQuarentena.REPROCESSADA)
                : ResolucaoQuarentenaDTO.repetida(existente(id));
    }

    public ResolucaoQuarentenaDTO descartar(long id) {
        ItemQuarentena item = existente(id);
        if (!item.pendente()) return ResolucaoQuarentenaDTO.repetida(item);
        boolean primeira = quarentena.resolver(id, ResolucaoQuarentena.DESCARTADA);
        LOG.infof("Quarentena descartada: id=%d topico=%s particao=%d offset=%d", id, item.mensagem().topico(),
                item.mensagem().particao(), item.mensagem().deslocamento());
        return primeira ? ResolucaoQuarentenaDTO.nova(id, ResolucaoQuarentena.DESCARTADA)
                : ResolucaoQuarentenaDTO.repetida(existente(id));
    }

    private ItemQuarentena existente(long id) {
        return quarentena.buscar(id).orElseThrow(() -> new RegraNegocioException(Categoria.NAO_ENCONTRADO,
                "QUARENTENA_DESCONHECIDA", "Mensagem de quarentena desconhecida."));
    }
}
