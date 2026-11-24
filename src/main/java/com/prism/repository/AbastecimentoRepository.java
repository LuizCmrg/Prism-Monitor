package com.prism.repository;

import com.prism.model.Abastecimento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Acesso à tabela "abastecimentos" (histórico de trocas de insumos).
 */
@Repository
public interface AbastecimentoRepository extends JpaRepository<Abastecimento, Long> {

    /** Histórico completo, da troca mais recente para a mais antiga (GET /api/abastecimentos). */
    List<Abastecimento> findAllByOrderByDataHoraDesc();

    /** Histórico de um equipamento, do mais recente para o mais antigo. */
    List<Abastecimento> findByIpOrderByDataHoraDesc(String ip);

    /** Histórico de vários equipamentos de uma vez (usado pelo PDF de checklist). */
    List<Abastecimento> findByIpInOrderByDataHoraDesc(Collection<String> ips);

    /** Última troca registrada de um IP. */
    Optional<Abastecimento> findTopByIpOrderByDataHoraDesc(String ip);

    /** Trocas dentro de um período. */
    List<Abastecimento> findByDataHoraBetweenOrderByDataHoraDesc(LocalDateTime inicio, LocalDateTime fim);
}
