package com.prism.repository;

import com.prism.model.Ativo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Acesso à tabela "ativos". Os métodos são traduzidos em consultas pelo nome.
 */
@Repository
public interface AtivoRepository extends JpaRepository<Ativo, Long> {

    /** Busca um equipamento pelo IP (identificador único na rede). */
    Optional<Ativo> findByIp(String ip);

    /** Verifica se um IP já está cadastrado. */
    boolean existsByIp(String ip);

    /** Lista usada por GET /api/status, ordenada por setor e, dentro do setor, por IP. */
    List<Ativo> findAllByOrderBySetorAscIpAsc();

    /** Busca vários equipamentos de uma vez. */
    List<Ativo> findByIpIn(Collection<String> ips);

    /** Quantidade de equipamentos online ou offline, usada nos cards do topo. */
    long countByOnline(Boolean online);

    /** Equipamentos com algum alerta de toner ou papel. */
    List<Ativo> findByAlertaTonerTrueOrAlertaPapelTrue();
}
