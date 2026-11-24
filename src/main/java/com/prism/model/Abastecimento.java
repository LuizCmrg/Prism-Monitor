package com.prism.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Registro de uma troca de insumos (toner ou papel) em um equipamento.
 *
 * O JSON sai em snake_case (ex.: insumosTrocados -> insumos_trocados),
 * no formato que o index.html consome.
 */
@Entity
@Table(name = "abastecimentos", indexes = {
        @Index(name = "idx_abastecimento_ip", columnList = "ip"),
        @Index(name = "idx_abastecimento_data", columnList = "data_hora")
})
@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Abastecimento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** IP do equipamento no momento da troca. */
    @Column(nullable = false, length = 45)
    private String ip;

    /** Setor copiado no momento da troca, para preservar o histórico. */
    @Column(length = 120)
    private String setor;

    @Column(length = 120)
    private String modelo;

    /** Número de série copiado no momento da troca. */
    @Column(length = 80)
    private String serie;

    /** Tipo exibido no histórico: SHIJIBIRA, OFFICE, EPSON ou ZEBRA. */
    @Column(name = "descricao_tipo", length = 20)
    private String descricaoTipo;

    /** Lista de insumos trocados, ex.: "Toner Preto", "Papel A4". */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "abastecimento_insumos",
                     joinColumns = @JoinColumn(name = "abastecimento_id"))
    @Column(name = "insumo", length = 60)
    private List<String> insumosTrocados = new ArrayList<>();

    /** Contador lido antes da troca (páginas). */
    @Column(name = "contador_anterior")
    private Long contadorAnterior;

    /** Contador lido no momento da troca (páginas). */
    @Column(name = "contador_atual")
    private Long contadorAtual;

    /** Páginas rodadas entre o contador anterior e o atual. */
    @Column(name = "delta_paginas")
    private Long deltaPaginas;

    /** Nível de toner no momento da troca, ex.: "Preto: 80% | Ciano: 50%". */
    @Column(name = "toner_nivel", length = 255)
    private String tonerNivel;

    /** Status do papel no momento da troca, ex.: "OK" ou "Pouco papel". */
    @Column(name = "papel_status", length = 60)
    private String papelStatus;

    /** Nome do técnico responsável pela troca. */
    @Column(length = 120)
    private String tecnico;

    /** Data e hora do registro, exibida já formatada pelo frontend. */
    @JsonFormat(pattern = "dd/MM/yyyy HH:mm:ss")
    @Column(name = "data_hora", nullable = false)
    private LocalDateTime dataHora = LocalDateTime.now();
}
