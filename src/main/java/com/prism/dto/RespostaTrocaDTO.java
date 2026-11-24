package com.prism.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Resposta do POST /api/abastecimento, lida pelo salvarAbastecimentoUnico() do index.html.
 *
 * Chaves JSON (snake_case):
 *   status, contador_anterior, contador_coletado, delta_paginas, toner_nivel, papel_status
 *
 * O frontend só exibe o resultado quando status == "sucesso".
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RespostaTrocaDTO(
        String status,
        Long contadorAnterior,
        Long contadorColetado,
        Long deltaPaginas,
        String tonerNivel,
        String papelStatus
) {

    public static final String STATUS_SUCESSO = "sucesso";
    public static final String STATUS_ERRO = "erro";

    /** Resposta de sucesso com os valores coletados no momento da troca. */
    public static RespostaTrocaDTO sucesso(Long contadorAnterior, Long contadorColetado,
                                           Long deltaPaginas, String tonerNivel, String papelStatus) {
        return new RespostaTrocaDTO(STATUS_SUCESSO, contadorAnterior, contadorColetado,
                deltaPaginas, tonerNivel, papelStatus);
    }

    /** Resposta de erro. */
    public static RespostaTrocaDTO erro() {
        return new RespostaTrocaDTO(STATUS_ERRO, null, null, null, "Indisponível", "Indisponível");
    }
}
