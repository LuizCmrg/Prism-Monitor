package com.prism.controller;

import com.prism.dto.RegistroTrocaRequest;
import com.prism.dto.RelatorioContadoresDTO;
import com.prism.dto.RespostaTrocaDTO;
import com.prism.model.Abastecimento;
import com.prism.model.Ativo;
import com.prism.repository.AbastecimentoRepository;
import com.prism.repository.AtivoRepository;
import com.prism.service.AbastecimentoService;
import com.prism.service.ColetorService;
import com.prism.service.RelatorioContadoresService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Endpoints REST consumidos pelo index.html.
 *
 * Os caminhos e as chaves JSON são os mesmos do frontend original:
 *   GET  /api/status
 *   POST /api/atualizar
 *   GET  /api/detalhes/{ip}
 *   GET  /api/abastecimentos
 *   POST /api/abastecimento
 *   GET  /api/relatorio-contadores
 *
 * Erros são tratados no GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final AtivoRepository ativoRepository;
    private final AbastecimentoRepository abastecimentoRepository;
    private final ColetorService coletorService;
    private final AbastecimentoService abastecimentoService;
    private final RelatorioContadoresService relatorioService;

    /** Lista completa de equipamentos, ordenada por setor e IP. */
    @GetMapping("/status")
    public List<Ativo> status() {
        return ativoRepository.findAllByOrderBySetorAscIpAsc();
    }

    /** Dispara uma coleta imediata e espera terminar. */
    @PostMapping("/atualizar")
    public Map<String, Object> atualizar() {
        int total = coletorService.coletarAgora();
        return Map.of("status", "ok", "total", total);
    }

    /** Detalhes de um equipamento pelo IP. Responde 404 se o IP não estiver cadastrado. */
    @GetMapping("/detalhes/{ip}")
    public ResponseEntity<Ativo> detalhes(@PathVariable String ip) {
        return ativoRepository.findByIp(ip)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Histórico de trocas, da mais recente para a mais antiga. */
    @GetMapping("/abastecimentos")
    public List<Abastecimento> abastecimentos() {
        return abastecimentoRepository.findAllByOrderByDataHoraDesc();
    }

    /**
     * Registra uma troca de insumos. Consulta o contador e os níveis no momento da troca
     * (quando a impressora responde) e calcula as páginas rodadas desde a última leitura.
     */
    @PostMapping("/abastecimento")
    public RespostaTrocaDTO registrarTroca(@Valid @RequestBody RegistroTrocaRequest requisicao) {
        return abastecimentoService.registrar(requisicao);
    }

    /**
     * BI de contadores por período, setor, marca e planta.
     * Parâmetros ausentes são ignorados pelo serviço.
     */
    @GetMapping("/relatorio-contadores")
    public RelatorioContadoresDTO relatorioContadores(
            @RequestParam(name = "data_inicio", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(name = "data_fim", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(name = "setor", required = false) String setor,
            @RequestParam(name = "marca", required = false) String marca,
            @RequestParam(name = "planta", required = false) String planta) {

        return relatorioService.gerar(dataInicio, dataFim, setor, marca, planta);
    }
}
