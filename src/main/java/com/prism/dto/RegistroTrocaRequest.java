package com.prism.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Corpo da requisição POST /api/abastecimento, enviado pelo index.html.
 *
 * Chaves JSON esperadas (snake_case):
 *   ip, setor, modelo, serie, tipo, contador_atual, descricao_tipo, insumos, tecnico
 *
 * O contador_atual chega como texto, porque o frontend pode enviar "N/A" ou "null".
 * A conversão para número fica no serviço.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RegistroTrocaRequest(

        @NotBlank(message = "IP é obrigatório")
        @Size(max = 45, message = "IP muito longo")
        String ip,

        @Size(max = 120, message = "Setor muito longo")
        String setor,

        @Size(max = 120, message = "Modelo muito longo")
        String modelo,

        @Size(max = 80, message = "Número de série muito longo")
        String serie,

        @Size(max = 60, message = "Tipo muito longo")
        String tipo,

        String contadorAtual,

        @Size(max = 20, message = "Descrição do tipo muito longa")
        String descricaoTipo,

        @NotEmpty(message = "Informe ao menos um insumo trocado")
        List<@NotBlank(message = "Insumo vazio") @Size(max = 60) String> insumos,

        @NotBlank(message = "Técnico responsável é obrigatório")
        @Size(max = 120, message = "Nome do técnico muito longo")
        String tecnico
) {}
