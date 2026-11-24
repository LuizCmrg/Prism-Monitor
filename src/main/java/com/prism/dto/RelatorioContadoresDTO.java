package com.prism.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Resposta do endpoint GET /api/relatorio-contadores (BI de contadores).
 *
 * Estrutura consumida pelo index.html:
 *   kpis       -> cartões do topo
 *   por_setor  -> tabela "Volume por Setor"
 *   por_data   -> volume diário
 *   detalhes   -> tabela "Detalhe por Equipamento"
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RelatorioContadoresDTO(
        Kpis kpis,
        List<PorSetor> porSetor,
        List<PorData> porData,
        List<Detalhe> detalhes
) {

    /** Indicadores do topo do BI. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Kpis(
            long totalPaginasPeriodo,
            int equipamentosAvaliados,
            long mediaPorEquipamento,
            String setorMaiorVolume
    ) {}

    /** Linha da tabela de volume por setor. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PorSetor(
            String setor,
            int equipamentos,
            long paginasPeriodo,
            long contadorAcumulado
    ) {}

    /** Volume de páginas agrupado por dia. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PorData(
            String data,
            long paginas
    ) {}

    /** Linha da tabela de detalhe por equipamento. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Detalhe(
            String setor,
            String ip,
            String modelo,
            long contadorInicial,
            long contadorFinal,
            long paginasPeriodo,
            String ultimaColeta
    ) {}
}
