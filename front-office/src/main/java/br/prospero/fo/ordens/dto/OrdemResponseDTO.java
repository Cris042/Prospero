package br.prospero.fo.ordens.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Ordem registrada e validada (formato, lote, tick, túnel). RECEBIDA aguarda o risco; RESERVADA tem "
        + "recurso reservado, sem implicar execução; AGUARDANDO_GATILHO identifica stop aceito no ME; REJEITADA traz motivoRejeicao.")
public record OrdemResponseDTO(
        UUID id, String ticker, String lado, String tipo, String validade, long quantidade,
        @Schema(nullable = true) String precoLimite,
        @Schema(nullable = true) String precoGatilho,
        @Schema(nullable = true) UUID grupoOco,
        @Schema(nullable = true, enumeration = {"TAKE_PROFIT", "STOP_LOSS"}) String papelOco,
        @Schema(enumeration = {"RECEBIDA", "RESERVADA", "REJEITADA", "AGUARDANDO_GATILHO", "ABERTA", "PARCIALMENTE_EXECUTADA", "EXECUTADA", "CANCELADA"}) String estado,
        @Schema(description = "Sessão da validade DIA (America/Sao_Paulo).") LocalDate dataSessao,
        Instant recebidaEm,
        @Schema(nullable = true, description = "Base do túnel aplicada no registro; null para Market e ordens anteriores à RF03.") String precoBaseTunel,
        @Schema(nullable = true, enumeration = {"ULTIMO", "REFERENCIA"}) String origemBase,
        @Schema(nullable = true, description = "Motivo do risco (ADR-012); somente em REJEITADA.", enumeration = {
                "SALDO_INSUFICIENTE", "CUSTODIA_INSUFICIENTE", "QUANTIDADE_MAXIMA_EXCEDIDA", "QUANTIDADE_FORA_DO_LOTE",
                "ATIVO_DESCONHECIDO", "CONTA_DESCONHECIDA", "PRECO_BASE_AUSENTE", "COMBINACAO_INVALIDA",
                "PRECO_ACIMA_DA_RESERVA", "PRECO_INVALIDO", "QUANTIDADE_INVALIDA", "SESSAO_ANTERIOR",
                "LIMITE_ORDENS_ATIVAS", "LIMITE_STOPS_PENDENTES", "LIMITE_POSICAO_EXCEDIDO", "EXPOSICAO_VENDIDA_EXCEDIDA"}) String motivoRejeicao,
        long quantidadeExecutada, long quantidadeCancelada,
        @Schema(nullable = true, description = "Motivo terminal do matching; não implica liquidação.") String motivoEncerramento,
        @Schema(description = "Quantidade retirada do Stop Loss OCO pela execução do Take Profit (RF05c.3b); 0 nas demais.")
        long quantidadeReduzida,
        @Schema(nullable = true, description = "Cancelamento manual solicitado (RF10); null sem pedido.")
        CancelamentoResumoDTO cancelamento) {
}
