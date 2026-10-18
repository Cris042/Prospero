package br.prospero.fo.ordens.helper;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.TipoOrdem;
import br.prospero.fo.ordens.dominio.Validade;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Matriz aceita pelo usuário em 2026-10-17: Market só IOC/FOK; Limit Dia/IOC/FOK; Stop e Stop Limit só Dia.
 * Campos de preço exigidos e proibidos por tipo são validação estrutural da RF02; tick/lote/túnel são RF03.
 */
public final class PoliticaOrdem {
    private static final Map<TipoOrdem, Set<Validade>> VALIDADES = Map.of(
            TipoOrdem.MARKET, EnumSet.of(Validade.IOC, Validade.FOK),
            TipoOrdem.LIMIT, EnumSet.of(Validade.DIA, Validade.IOC, Validade.FOK),
            TipoOrdem.STOP, EnumSet.of(Validade.DIA),
            TipoOrdem.STOP_LIMIT, EnumSet.of(Validade.DIA));

    private PoliticaOrdem() {
    }

    public static void validar(TipoOrdem tipo, Validade validade, BigDecimal precoLimite, BigDecimal precoGatilho) {
        if (!VALIDADES.get(tipo).contains(validade)) {
            throw regra("COMBINACAO_INVALIDA", "Validade não permitida para o tipo de ordem.");
        }
        boolean exigeLimite = tipo == TipoOrdem.LIMIT || tipo == TipoOrdem.STOP_LIMIT;
        boolean exigeGatilho = tipo == TipoOrdem.STOP || tipo == TipoOrdem.STOP_LIMIT;
        if ((exigeLimite && precoLimite == null) || (exigeGatilho && precoGatilho == null)) {
            throw regra("CAMPO_EXIGIDO_PELO_TIPO", "Preço limite ou de gatilho ausente para o tipo de ordem.");
        }
        if ((!exigeLimite && precoLimite != null) || (!exigeGatilho && precoGatilho != null)) {
            throw regra("CAMPO_PROIBIDO_PELO_TIPO", "Preço informado não se aplica ao tipo de ordem.");
        }
    }

    /**
     * OCO protege posição: venda com alvo acima do stop; compra (cobertura de vendida) com alvo abaixo do stop.
     * A existência da posição é verificada na RF04.
     */
    public static void validarOco(LadoOrdem lado, TipoOrdem tipoStopLoss, BigDecimal alvo, BigDecimal gatilho) {
        if (tipoStopLoss != TipoOrdem.STOP && tipoStopLoss != TipoOrdem.STOP_LIMIT) {
            throw regra("OCO_TIPO_INVALIDO", "Stop Loss do OCO deve ser STOP ou STOP_LIMIT.");
        }
        int comparacao = alvo.compareTo(gatilho);
        if (lado == LadoOrdem.VENDA ? comparacao <= 0 : comparacao >= 0) {
            throw regra("OCO_PRECOS_INCOERENTES", "Take Profit e Stop Loss em lados incoerentes do preço.");
        }
    }

    static RegraNegocioException regra(String codigo, String mensagem) {
        return new RegraNegocioException(Categoria.REGRA_VIOLADA, codigo, mensagem);
    }
}
