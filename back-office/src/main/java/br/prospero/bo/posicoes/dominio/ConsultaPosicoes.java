package br.prospero.bo.posicoes.dominio;

import java.util.List;

public interface ConsultaPosicoes {
    List<EstadoPosicao> consultar(String conta);
}
