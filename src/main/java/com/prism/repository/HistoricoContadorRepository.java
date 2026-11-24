package com.prism.repository;

import com.prism.model.HistoricoContador;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Acesso à tabela "historico_contadores".
 *
 * Usado pelo coletor (evitar snapshot duplicado), pela troca de insumos
 * (contador anterior) e pelo relatório/BI de contadores.
 */
@Repository
public interface HistoricoContadorRepository extends JpaRepository<HistoricoContador, Long> {

    /** Verifica se já existe snapshot deste IP, nesta data, com este valor. */
    boolean existsByIpAndDataDiaAndContador(String ip, LocalDate dataDia, Long contador);

    /** Último snapshot de um IP. Serve como "contador anterior" ao registrar uma troca. */
    Optional<HistoricoContador> findTopByIpOrderByDataHoraDesc(String ip);

    /** Histórico completo de um IP, em ordem cronológica. */
    List<HistoricoContador> findByIpOrderByDataHoraAsc(String ip);

    /**
     * Leituras dentro de um período, para o BI de contadores.
     *
     * Os filtros opcionais chegam como string vazia ("") quando não usados.
     * Usamos "" em vez de null porque o PostgreSQL não consegue inferir o tipo
     * de um parâmetro nulo dentro de LIKE/UPPER.
     */
    @Query("""
            SELECT h FROM HistoricoContador h
            WHERE h.dataHora BETWEEN :inicio AND :fim
              AND (:setor = '' OR LOWER(h.setor) LIKE LOWER(CONCAT('%', :setor, '%')))
              AND (:marca = '' OR LOWER(h.marca) LIKE LOWER(CONCAT('%', :marca, '%')))
              AND (:planta = '' OR UPPER(h.site) = UPPER(:planta))
            ORDER BY h.ip ASC, h.dataHora ASC
            """)
    List<HistoricoContador> buscarPorPeriodo(@Param("inicio") LocalDateTime inicio,
                                             @Param("fim") LocalDateTime fim,
                                             @Param("setor") String setor,
                                             @Param("marca") String marca,
                                             @Param("planta") String planta);
}
