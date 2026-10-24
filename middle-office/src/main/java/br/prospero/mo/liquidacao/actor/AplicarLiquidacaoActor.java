package br.prospero.mo.liquidacao.actor;

import br.prospero.mo.liquidacao.dominio.ContratoRecusado;
import br.prospero.mo.liquidacao.dominio.Desfecho;
import br.prospero.mo.liquidacao.dominio.EventoLiquidacao;
import br.prospero.mo.liquidacao.dominio.MotivoRecusa;
import br.prospero.mo.liquidacao.dominio.RegistroLiquidacao;
import br.prospero.mo.liquidacao.dominio.RegrasLiquidacao;
import br.prospero.mo.liquidacao.dominio.RepositorioLiquidacao;
import br.prospero.mo.liquidacao.helper.EnvelopeLiquidacao;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Um evento de liquidacao.eventos.v1 por transação (ADR-016): crédito, aguardando ou recusa persistidos; a partição
 * sempre avança. Só falha técnica (banco, invariante) propaga e repete sem descarte. Logs com posição e motivo apenas.
 */
@ApplicationScoped
public class AplicarLiquidacaoActor {
    private static final Logger LOG = Logger.getLogger(AplicarLiquidacaoActor.class);
    private final RepositorioLiquidacao repositorio;
    private final long teto;

    @Inject
    public AplicarLiquidacaoActor(RepositorioLiquidacao repositorio,
                                  @ConfigProperty(name = "prospero.liquidacao.aguardando.teto", defaultValue = "10000")
                                  long teto) {
        this.repositorio = repositorio;
        this.teto = teto;
    }

    @Transactional
    public Desfecho aplicar(RegistroLiquidacao registro) {
        EventoLiquidacao evento;
        try {
            evento = EnvelopeLiquidacao.ler(registro.valor());
        } catch (ContratoRecusado recusa) {
            return recusar(recusa.id(), recusa.assinatura(), recusa.motivo(), registro);
        }
        String conta = evento.credito().conta();
        if (!conta.equals(registro.chave())) {
            return recusar(evento.id(), evento.assinatura(), MotivoRecusa.CHAVE_INCOERENTE, registro);
        }
        repositorio.bloquear(conta, evento.id());
        Desfecho desfecho = RegrasLiquidacao.desfecho(evento, repositorio.expectativa(evento.id()),
                repositorio.aplicada(evento.id()), repositorio.aguardando(evento.id(), evento.assinatura()),
                repositorio::totalAguardando, teto);
        switch (desfecho.tipo()) {
            case CREDITAR -> repositorio.creditar(evento.id(), evento.assinatura(), evento.credito());
            case AGUARDAR -> repositorio.aguardar(evento, registro.particao(), registro.deslocamento());
            case RECUSAR -> recusar(evento.id(), evento.assinatura(), desfecho.motivo(), registro);
            case REPETICAO -> { }
        }
        return desfecho;
    }

    private Desfecho recusar(UUID id, String assinatura, MotivoRecusa motivo, RegistroLiquidacao registro) {
        repositorio.recusar(id, assinatura, motivo, registro.particao(), registro.deslocamento());
        LOG.warnf("Liquidação recusada: particao=%d offset=%d motivo=%s", registro.particao(), registro.deslocamento(),
                motivo);
        return Desfecho.recusar(motivo);
    }
}
