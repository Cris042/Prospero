package br.prospero.fo.ordens.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Intenção do investidor registrada pelo FO. Preços ausentes são null conforme o tipo;
 * grupoEnvio liga a ordem (ou o par OCO) à chave de idempotência que a criou;
 * baseTunel é null para Market, cuja proteção de preço é feita no matching (RF05);
 * motivoRejeicao existe somente em REJEITADA (RF04).
 */
public record Ordem(UUID id, String conta, String ticker, LadoOrdem lado, TipoOrdem tipo, Validade validade,
                    long quantidade, BigDecimal precoLimite, BigDecimal precoGatilho, UUID grupoOco,
                    PapelOco papelOco, UUID grupoEnvio, EstadoOrdem estado, LocalDate dataSessao,
                    Instant recebidaEm, BaseTunel baseTunel, MotivoRejeicao motivoRejeicao,
                    long quantidadeExecutada, long quantidadeCancelada, String motivoEncerramento) {
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
        if ((estado == EstadoOrdem.REJEITADA) != (motivoRejeicao != null)) {
            throw new IllegalArgumentException("Motivo existe somente na rejeição.");
        }
        if (quantidadeExecutada < 0 || quantidadeCancelada < 0
                || quantidadeExecutada > quantidade || quantidadeCancelada > quantidade - quantidadeExecutada) {
            throw new IllegalArgumentException("Progresso da ordem inválido.");
        }
    }

    public Ordem(UUID id, String conta, String ticker, LadoOrdem lado, TipoOrdem tipo, Validade validade,
                 long quantidade, BigDecimal precoLimite, BigDecimal precoGatilho, UUID grupoOco, PapelOco papelOco,
                 UUID grupoEnvio, EstadoOrdem estado, LocalDate dataSessao, Instant recebidaEm, BaseTunel baseTunel,
                 MotivoRejeicao motivoRejeicao) {
        this(id, conta, ticker, lado, tipo, validade, quantidade, precoLimite, precoGatilho, grupoOco, papelOco,
                grupoEnvio, estado, dataSessao, recebidaEm, baseTunel, motivoRejeicao, 0, 0, null);
    }

    /** Ordem recém-registrada, ainda sem resultado do risco. */
    public Ordem(UUID id, String conta, String ticker, LadoOrdem lado, TipoOrdem tipo, Validade validade,
                 long quantidade, BigDecimal precoLimite, BigDecimal precoGatilho, UUID grupoOco, PapelOco papelOco,
                 UUID grupoEnvio, EstadoOrdem estado, LocalDate dataSessao, Instant recebidaEm, BaseTunel baseTunel) {
        this(id, conta, ticker, lado, tipo, validade, quantidade, precoLimite, precoGatilho, grupoOco, papelOco,
                grupoEnvio, estado, dataSessao, recebidaEm, baseTunel, null);
    }
}
