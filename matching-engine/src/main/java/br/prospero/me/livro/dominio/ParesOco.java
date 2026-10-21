package br.prospero.me.livro.dominio;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Pares OCO vivos de um ativo, indexados pelas duas pernas; mutável só dentro do escritor único. */
final class ParesOco {
    static final class Par {
        final Admissao takeProfit;
        Admissao stopLoss;
        long executadaTakeProfit;

        Par(Admissao takeProfit, Admissao stopLoss) {
            this.takeProfit = takeProfit;
            this.stopLoss = stopLoss;
        }
    }

    private final Map<UUID, Par> porTakeProfit = new HashMap<>();
    private final Map<UUID, Par> porStopLoss = new HashMap<>();

    Par registrar(Admissao takeProfit, Admissao stopLoss) {
        Par par = new Par(takeProfit, stopLoss);
        porTakeProfit.put(takeProfit.ordem(), par);
        porStopLoss.put(stopLoss.ordem(), par);
        return par;
    }

    Par takeProfit(UUID ordem) {
        return porTakeProfit.get(ordem);
    }

    Par stopLoss(UUID ordem) {
        return porStopLoss.get(ordem);
    }

    boolean vivo(Par par) {
        return porTakeProfit.get(par.takeProfit.ordem()) == par;
    }

    /** Desfaz o vínculo; a partir daqui cada perna restante segue como ordem simples. */
    Par dissolverPorTakeProfit(UUID ordem) {
        Par par = porTakeProfit.remove(ordem);
        if (par != null) {
            porStopLoss.remove(par.stopLoss.ordem());
        }
        return par;
    }

    Par dissolverPorStopLoss(UUID ordem) {
        Par par = porStopLoss.remove(ordem);
        if (par != null) {
            porTakeProfit.remove(par.takeProfit.ordem());
        }
        return par;
    }

    void limpar() {
        porTakeProfit.clear();
        porStopLoss.clear();
    }
}
