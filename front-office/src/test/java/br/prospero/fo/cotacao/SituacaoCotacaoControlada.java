package br.prospero.fo.cotacao;

import br.prospero.fo.cotacao.dominio.SituacaoCotacao;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

@Alternative
@Priority(1)
@ApplicationScoped
public class SituacaoCotacaoControlada implements SituacaoCotacao {
    private volatile boolean emDia;
    public void definir(boolean emDia) { this.emDia = emDia; }
    @Override public boolean emDia() { return emDia; }
}
