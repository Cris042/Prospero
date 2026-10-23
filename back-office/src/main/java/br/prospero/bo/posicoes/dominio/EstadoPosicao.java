package br.prospero.bo.posicoes.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;

public record EstadoPosicao(String ticker, Posicao posicao, BigDecimal cotacao, LocalDate sessao,
                            boolean encerrada, long revisao, Instant atualizadaEm) { }
