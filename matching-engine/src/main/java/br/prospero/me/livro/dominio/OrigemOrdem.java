package br.prospero.me.livro.dominio;

import java.time.LocalDate;
import java.util.UUID;

/** Proveniência da admissão, conservada somente enquanto a ordem estiver viva. Não define elegibilidade. */
record OrigemOrdem(TipoOrdem tipoOriginal, UUID intencao, String conta, String ticker, LocalDate sessao,
                   UUID takeProfit, UUID stopLoss) {
}
