package br.prospero.fo.atualizacoes.helper;


/** O laboratório publica HTTP em loopback; não aceita outra porta/origem ou cabeçalhos encaminhados. */
public final class OrigemAtualizacoes {
    private OrigemAtualizacoes() { }

    public static boolean permitida(String origem, String host) {
        if (origem == null || host == null || origem.length() > 100 || host.length() > 40
                || !host.matches("(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?")) return false;
        int separador = host.lastIndexOf(':');
        if (separador >= 0 && Integer.parseInt(host.substring(separador + 1)) > 65535) return false;
        return origem.equals("http://" + host);
    }
}
