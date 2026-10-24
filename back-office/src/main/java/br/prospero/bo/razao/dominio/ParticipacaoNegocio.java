package br.prospero.bo.razao.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Lado de um negócio visto pela própria conta; não carrega a conta nem a ordem da contraparte. */
public record ParticipacaoNegocio(long sequencia, String negocio, String ticker, LadoNegocio lado, UUID ordem,
                                  LocalDate sessao, LocalDate liquidacao, BigDecimal preco, long quantidade,
                                  BigDecimal valor, EstadoLiquidacao estado) {
}
