package br.prospero.bo.razao.dominio;

import java.util.List;

/** Leituras limitadas e filtradas pela conta do token, em ordem decrescente de sequência. */
public interface ConsultaRazao {
    List<ParticipacaoNegocio> negocios(String conta, long antesDe, int limite);

    /** Lados do negócio que pertencem à conta; vazio quando inexistente ou sem participação. */
    List<ParticipacaoNegocio> negocio(String conta, String negocio);

    List<LancamentoRazao> lancamentos(String conta, long antesDe, int limite);
}
