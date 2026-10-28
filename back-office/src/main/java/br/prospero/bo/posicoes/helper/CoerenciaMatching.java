package br.prospero.bo.posicoes.helper;

import br.prospero.bo.posicoes.dominio.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Valida contratos independentes do produtor antes de admitir fatos na projeção. */
public final class CoerenciaMatching {
    private CoerenciaMatching() { }

    public static void validar(EventoMatching e) {
        if (e.detalhe() instanceof EventoMatching.SessaoEncerrada marco) {
            UUID causa = EventoMatching.encerramento(e.ticker(), e.sessao());
            UUID id = UUID.nameUUIDFromBytes(("matching:" + causa + ":" + marco.expiradas()).getBytes(StandardCharsets.UTF_8));
            if (!causa.equals(e.causacao()) || !causa.equals(e.correlacao()) || !id.equals(e.id())) falhar("MARCO");
            return;
        }
        EventoMatching.Alvo alvo = alvo(e);
        if (alvo != null && !e.correlacao().equals(UUID.nameUUIDFromBytes(
                ("admissao:" + alvo.intencao()).getBytes(StandardCharsets.UTF_8)))) falhar("CORRELACAO");
        if (e.versao() == 4 && !EventoMatching.encerramento(e.ticker(), e.sessao()).equals(e.causacao())) falhar("CAUSACAO");
        if (e.detalhe() instanceof EventoMatching.Negocio n) {
            BigDecimal tick = new BigDecimal(e.ticker().equals("OMEGA3") ? "0.05" : "0.01");
            int lote = e.ticker().equals("OMEGA3") ? 100 : 1;
            if (n.preco().remainder(tick).signum() != 0 || n.quantidade() % lote != 0) falhar("TICK_LOTE");
        }
    }

    private static EventoMatching.Alvo alvo(EventoMatching e) {
        return switch (e.detalhe()) {
            case EventoMatching.Negocio n -> n.agressor() == LadoOrdem.COMPRA ? n.compra() : n.venda();
            case EventoMatching.Aceita a -> a.alvo();
            case EventoMatching.Oferta o -> o.alvo();
            case EventoMatching.Rejeitada r -> r.alvo();
            case EventoMatching.StopPendente s -> s.alvo();
            case EventoMatching.StopAcionado s -> s.alvo();
            case EventoMatching.Reduzida r -> r.alvo();
            case EventoMatching.Encerrada f -> e.causacao() == null ? null : f.alvo();
            case EventoMatching.SessaoEncerrada ignorado -> null;
            case EventoMatching.CancelamentoDecidido d -> d.alvo();
        };
    }

    private static void falhar(String motivo) { throw new ProjecaoIncoerenteException(motivo); }
}
