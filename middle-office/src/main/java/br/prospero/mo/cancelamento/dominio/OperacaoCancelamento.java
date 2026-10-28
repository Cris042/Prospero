package br.prospero.mo.cancelamento.dominio;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Linha de cancelamento_operacao, uma por ordem. Registro de persistência (exceção às regras 8 e 9 do Object
 * Calisthenics, como {@code ReservaMatching}): espelha a linha sem comportamento além das transições de valor.
 *
 * @param assinatura SHA-256 da identidade do pedido: repetição igual devolve a operação, diferente é inconsistência
 * @param decisao    resultado do ME (APLICADO ou recusa), nulo na recusa local e antes da decisão
 */
public record OperacaoCancelamento(UUID ordem, UUID intencao, UUID admissao, String conta, String ticker,
                                   LocalDate dataSessao, UUID solicitacao, UUID comando, String assinatura,
                                   EstadoCancelamento estado, UUID terminal, String decisao, MotivoRecusa motivo,
                                   String traceparent) {
    public OperacaoCancelamento {
        Objects.requireNonNull(ordem);
        Objects.requireNonNull(intencao);
        Objects.requireNonNull(admissao);
        Objects.requireNonNull(conta);
        Objects.requireNonNull(ticker);
        Objects.requireNonNull(dataSessao);
        Objects.requireNonNull(solicitacao);
        Objects.requireNonNull(comando);
        Objects.requireNonNull(assinatura);
        Objects.requireNonNull(estado);
    }

    /** Operação recém-aceita, antes de decidir entre esperar o ACK, emitir o comando ou recusar. */
    public static OperacaoCancelamento nova(SolicitacaoCancelamento pedido, UUID admissao, UUID comando, String assinatura,
                                            String traceparent) {
        return new OperacaoCancelamento(pedido.ordem(), pedido.intencao(), admissao, pedido.conta(), pedido.ticker(),
                pedido.dataSessao(), pedido.id(), comando, assinatura, EstadoCancelamento.AGUARDANDO_ACK, null, null, null,
                traceparent);
    }

    public OperacaoCancelamento comEstado(EstadoCancelamento novo) {
        return new OperacaoCancelamento(ordem, intencao, admissao, conta, ticker, dataSessao, solicitacao, comando,
                assinatura, novo, terminal, decisao, motivo, traceparent);
    }

    /** Recusa final; a decisão só existe quando veio do ME. */
    public OperacaoCancelamento recusada(MotivoRecusa porque, String decisaoDoMatching) {
        return new OperacaoCancelamento(ordem, intencao, admissao, conta, ticker, dataSessao, solicitacao, comando,
                assinatura, EstadoCancelamento.RECUSADO, terminal, decisaoDoMatching, porque, traceparent);
    }

    public OperacaoCancelamento confirmada() {
        return new OperacaoCancelamento(ordem, intencao, admissao, conta, ticker, dataSessao, solicitacao, comando,
                assinatura, EstadoCancelamento.CONFIRMADO, terminal, "APLICADO", null, traceparent);
    }
}
