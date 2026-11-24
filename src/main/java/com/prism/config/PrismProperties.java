package com.prism.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades tipadas do prefixo "prism", definidas em application.properties.
 *
 * Mapeamento:
 *   prism.importacao.planilha       -> importacao().planilha()
 *   prism.coleta.intervalo-ms       -> coleta().intervaloMs()
 *   prism.impressoras.senha         -> impressoras().senha()
 */
@ConfigurationProperties(prefix = "prism")
public record PrismProperties(
        Importacao importacao,
        Coleta coleta,
        Impressoras impressoras
) {

    /** Importação inicial da lista de equipamentos a partir da planilha. */
    public record Importacao(
            String planilha,
            boolean habilitada
    ) {}

    /** Parâmetros da coleta paralela e do ping. */
    public record Coleta(
            long intervaloMs,
            int threads,
            int pingTimeoutMs
    ) {}

    /** Credenciais e opções de acesso ao Web Image Monitor das impressoras Ricoh. */
    public record Impressoras(
            String usuario,
            String senha,
            boolean ignorarSsl
    ) {}
}
