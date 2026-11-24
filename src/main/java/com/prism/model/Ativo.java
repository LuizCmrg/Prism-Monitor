package com.prism.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Representa um equipamento monitorado (impressora Ricoh, Epson ou Zebra).
 *
 * Os nomes dos campos em Java seguem camelCase. O Jackson converte para snake_case
 * na serialização (ex.: numeroSerie -> numero_serie), exatamente como o index.html consome.
 */
@Entity
@Table(name = "ativos")
@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Ativo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nome do setor ou local, exibido como título do card. */
    @Column(length = 120)
    private String setor;

    /** Local complementar (usado para identificar a planta CLP). */
    @Column(length = 120)
    private String local;

    @Column(length = 60)
    private String marca;

    @Column(length = 120)
    private String modelo;

    @Column(name = "numero_serie", length = 80)
    private String numeroSerie;

    /** Endereço IP na rede. Identificador único do equipamento. */
    @Column(nullable = false, unique = true, length = 45)
    private String ip;

    /** Código IMP informado na planilha. */
    @Column(length = 40)
    private String imp;

    /** Planta: SOR2 ou CLP. */
    @Column(length = 20)
    private String site;

    /** Tipo de equipamento (ex.: RICOH, EPSON, ZEBRA, CLP). */
    @Column(length = 40)
    private String tipo;

    @Column(length = 60)
    private String versao;

    /** Resultado do último ping. */
    private Boolean online = false;

    /** Equipamento crítico para a linha de produção. */
    private Boolean critica = false;

    /** Impressora Shijibira (linha de montagem). */
    private Boolean shijibira = false;

    /** Níveis de toner no formato "Preto: 80%" ou "Ciano: 50% | Magenta: 70%". */
    @Column(length = 255)
    private String toner;

    @Column(name = "alerta_toner")
    private Boolean alertaToner = false;

    @Column(name = "alerta_papel")
    private Boolean alertaPapel = false;

    @Column(name = "papel_vazio")
    private Boolean papelVazio = false;

    @Column(name = "papel_quase_vazio")
    private Boolean papelQuaseVazio = false;

    /** Contador total de páginas, lido via Web Image Monitor. */
    @Column(name = "contador_total")
    private Long contadorTotal;

    /** Data da última leitura do contador, já formatada para exibição. */
    @Column(name = "contador_data", length = 30)
    private String contadorData;

    /** Mensagem de erro da última tentativa de leitura do contador. */
    @Column(name = "contador_erro", length = 255)
    private String contadorErro;

    /** Data e hora da última verificação de conectividade. */
    @Column(name = "ultima_verificacao")
    private LocalDateTime ultimaVerificacao;
}
