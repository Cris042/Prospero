package br.prospero.mo.cancelamento.dominio;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistência da coordenação; todas as operações participam da transação do caso de uso. */
public interface RepositorioCancelamento {
    Optional<OperacaoCancelamento> buscar(UUID ordem);

    /** Lê a operação com lock de escrita, para aplicar terminal ou decisão do ME. */
    Optional<OperacaoCancelamento> travar(UUID ordem);

    /** Reserva da intenção com admissão comprovada (ordem, sessão e recibo da admissão derivada); vazio no legado. */
    Optional<ReservaAlvo> reservaComprovada(UUID intencao);

    /** Trava o recibo da admissão e informa se o primeiro ACK já foi gravado; serializa solicitação e ACK. */
    boolean travarReciboConfirmado(UUID admissao);

    void inserir(OperacaoCancelamento operacao);

    /** Move AGUARDANDO_ACK das admissões informadas para COMANDO_EMITIDO e devolve as operações promovidas. */
    List<OperacaoCancelamento> promoverAguardando(Collection<UUID> admissoes);

    void registrarTerminal(UUID ordem, UUID terminal);

    /** Conclui COMANDO_EMITIDO ou TERMINAL_APLICADO em CONFIRMADO ou RECUSADO. */
    void concluir(OperacaoCancelamento concluida);
}
