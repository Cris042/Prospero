package br.prospero.fo.ordens.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Intenção do investidor registrada pelo FO. Preços ausentes são null conforme o tipo;
 * grupoEnvio liga a ordem (ou o par OCO) à chave de idempotência que a criou;
 * baseTunel é null para Market, cuja proteção de preço é feita no matching (RF05).
 */
public record Ordem(UUID id, String conta, String ticker, LadoOrdem lado, TipoOrdem tipo, Validade validade,
                    long quantidade, BigDecimal precoLimite, BigDecimal precoGatilho, UUID grupoOco,
                    PapelOco papelOco, UUID grupoEnvio, EstadoOrdem estado, LocalDate dataSessao,
                    Instant recebidaEm, BaseTunel baseTunel) {
    public Ordem {
        Objects.requireNonNull(id);
        Objects.requireNonNull(conta);
        Objects.requireNonNull(ticker);
        Objects.requireNonNull(lado);
        Objects.requireNonNull(tipo);
        Objects.requireNonNull(validade);
        Objects.requireNonNull(grupoEnvio);
        Objects.requireNonNull(estado);
        Objects.requireNonNull(dataSessao);
        Objects.requireNonNull(recebidaEm);
        if (quantidade <= 0) {
            throw new IllegalArgumentException("Quantidade deve ser positiva.");
        }
    }
}
