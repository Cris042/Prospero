package br.prospero.fo.atualizacoes.dominio;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Orçamento local de sessões e leases do upgrade; o adaptador fornece o relógio. */
public final class ControleSessoes {
    private static final Set<String> CONTAS = Set.of("CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004");
    private final Map<String, Sessao> sessoes = new HashMap<>();
    private record Sessao(String conta, long expiraEm) { }

    public synchronized boolean reservar(String conta, String id, long expiraEm) {
        if (conta == null || !CONTAS.contains(conta) || id == null || id.isBlank() || id.length() > 100 || expiraEm <= 0
                || sessoes.containsKey(id) || sessoes.size() >= 32) return false;
        long daConta = sessoes.values().stream().filter(s -> s.conta().equals(conta)).count();
        if (daConta >= 8) return false;
        sessoes.put(id, new Sessao(conta, expiraEm));
        return true;
    }

    public synchronized boolean confirmar(String id, long expiraEm) {
        Sessao sessao = sessoes.get(id);
        if (sessao == null || expiraEm <= 0) return false;
        sessoes.put(id, new Sessao(sessao.conta(), expiraEm));
        return true;
    }

    public synchronized void liberar(String id) { sessoes.remove(id); }

    public synchronized Set<String> expurgar(long agora) {
        Set<String> expiradas = new HashSet<>();
        sessoes.forEach((id, sessao) -> { if (sessao.expiraEm() <= agora) expiradas.add(id); });
        expiradas.forEach(sessoes::remove);
        return Set.copyOf(expiradas);
    }
}
