package com.prism.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Snapshot do contador total de páginas de um equipamento em um momento da coleta.
 *
 * Um registro por IP por dia e por valor de contador. A constraint única
 * impede gravar duas vezes o mesmo valor para o mesmo IP no mesmo dia.
 */
@Entity
@Table(name = "historico_contadores",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_historico_ip_dia_contador",
                columnNames = {"ip", "data_dia", "contador"}),
        indexes = {
                @Index(name = "idx_historico_ip", columnList = "ip"),
                @Index(name = "idx_historico_data", columnList = "data_hora"),
                @Index(name = "idx_historico_setor", columnList = "setor")
        })
@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class HistoricoContador {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** IP do equipamento no momento da leitura. */
    @Column(nullable = false, length = 45)
    private String ip;

    /** Setor copiado no momento da leitura, para o BI não mudar se o cadastro for alterado. */
    @Column(length = 120)
    private String setor;

    @Column(length = 60)
    private String marca;

    @Column(length = 120)
    private String modelo;

    @Column(length = 80)
    private String serie;

    /** Planta da leitura: SOR2 ou CLP. Usada no filtro "planta" do BI. */
    @Column(length = 20)
    private String site;

    /** Valor do contador total de páginas lido. */
    @Column(nullable = false)
    private Long contador;

    /** Data e hora exata da leitura. */
    @JsonFormat(pattern = "dd/MM/yyyy HH:mm:ss")
    @Column(name = "data_hora", nullable = false)
    private LocalDateTime dataHora = LocalDateTime.now();

    /** Apenas a data da leitura. Parte da chave única, evita duplicidade no mesmo dia. */
    @JsonFormat(pattern = "dd/MM/yyyy")
    @Column(name = "data_dia", nullable = false)
    private LocalDate dataDia = LocalDate.now();
}
