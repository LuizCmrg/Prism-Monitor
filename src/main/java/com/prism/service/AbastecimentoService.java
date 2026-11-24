package com.prism.service;

import com.prism.dto.RegistroTrocaRequest;
import com.prism.dto.RespostaTrocaDTO;
import com.prism.model.Abastecimento;
import com.prism.model.Ativo;
import com.prism.model.HistoricoContador;
import com.prism.repository.AbastecimentoRepository;
import com.prism.repository.AtivoRepository;
import com.prism.repository.HistoricoContadorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

/**
 * Registra uma troca de insumos.
 *
 * Fluxo:
 *   1. Descobre o contador anterior (valor que o técnico viu na tela ou último snapshot).
 *   2. Nas Ricoh, consulta a impressora no momento da troca (contador, toner, papel).
 *   3. Calcula as páginas rodadas e grava a troca, atualiza o equipamento e o histórico de contadores.
 *
 * A troca é registrada mesmo quando a impressora não responde.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AbastecimentoService {

    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final String INDISPONIVEL = "Indisponível";

    private final AtivoRepository ativoRepository;
    private final AbastecimentoRepository abastecimentoRepository;
    private final HistoricoContadorRepository historicoRepository;
    private final RicohWebService ricohWebService;
    private final TransactionTemplate transactionTemplate;

    /**
     * Registra a troca e devolve os valores usados pelo alerta do frontend.
     *
     * @throws IllegalArgumentException se o IP não estiver cadastrado
     */
    public RespostaTrocaDTO registrar(RegistroTrocaRequest requisicao) {
        String ip = requisicao.ip().trim();

        Ativo ativo = ativoRepository.findByIp(ip)
                .orElseThrow(() -> new IllegalArgumentException("Equipamento não cadastrado: " + ip));

        // Consulta a impressora fora da transação do banco.
        RicohWebService.LeituraRicoh leitura = isRicoh(ativo) ? lerImpressora(ip) : null;

        Long contadorAnterior = obterContadorAnterior(requisicao, ip);
        LocalDateTime agora = LocalDateTime.now();

        return transactionTemplate.execute(status ->
                gravar(requisicao, ip, contadorAnterior, leitura, agora));
    }

    /** Grava a troca e atualiza o equipamento. Roda dentro de uma transação. */
    private RespostaTrocaDTO gravar(RegistroTrocaRequest requisicao, String ip, Long contadorAnterior,
                                    RicohWebService.LeituraRicoh leitura, LocalDateTime agora) {
        Ativo ativo = ativoRepository.findByIp(ip)
                .orElseThrow(() -> new IllegalArgumentException("Equipamento não cadastrado: " + ip));

        Long contadorColetado = leitura != null ? leitura.contadorTotal() : null;
        Long deltaPaginas = (contadorAnterior != null && contadorColetado != null)
                ? Math.max(0L, contadorColetado - contadorAnterior)
                : null;
        String tonerNivel = (leitura != null && leitura.toner() != null) ? leitura.toner() : INDISPONIVEL;
        String papelStatus = statusPapel(leitura);

        Abastecimento troca = new Abastecimento();
        troca.setIp(ip);
        troca.setSetor(ativo.getSetor());
        troca.setModelo(ativo.getModelo());
        troca.setSerie(ativo.getNumeroSerie());
        troca.setDescricaoTipo(descricaoTipo(requisicao, ativo));
        troca.setInsumosTrocados(requisicao.insumos().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        troca.setContadorAnterior(contadorAnterior);
        troca.setContadorAtual(contadorColetado);
        troca.setDeltaPaginas(deltaPaginas);
        troca.setTonerNivel(tonerNivel);
        troca.setPapelStatus(papelStatus);
        troca.setTecnico(requisicao.tecnico().trim());
        troca.setDataHora(agora);
        abastecimentoRepository.save(troca);

        atualizarEquipamento(ativo, leitura, contadorColetado, agora);

        return RespostaTrocaDTO.sucesso(contadorAnterior, contadorColetado, deltaPaginas, tonerNivel, papelStatus);
    }

    /**
     * Contador anterior: o valor que o técnico viu na tela (contador_atual do request).
     * Se ele vier vazio ou inválido, usa o último snapshot do histórico.
     */
    private Long obterContadorAnterior(RegistroTrocaRequest requisicao, String ip) {
        Long daTela = parseContador(requisicao.contadorAtual());
        if (daTela != null) {
            return daTela;
        }
        return historicoRepository.findTopByIpOrderByDataHoraDesc(ip)
                .map(HistoricoContador::getContador)
                .orElse(null);
    }

    /** Consulta o WIM da impressora. Qualquer falha vira "sem leitura", sem cancelar a troca. */
    private RicohWebService.LeituraRicoh lerImpressora(String ip) {
        try {
            return ricohWebService.ler(ip);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Leitura de {} interrompida durante a troca.", ip);
            return null;
        } catch (IOException | IllegalStateException e) {
            log.warn("Não foi possível ler {} durante a troca: {}", ip, e.getMessage());
            return null;
        }
    }

    /** Atualiza os dados do equipamento com a leitura feita na troca. */
    private void atualizarEquipamento(Ativo ativo, RicohWebService.LeituraRicoh leitura,
                                      Long contadorColetado, LocalDateTime agora) {
        if (leitura == null) {
            return;
        }

        ativo.setOnline(true);
        ativo.setUltimaVerificacao(agora);
        ativo.setToner(leitura.toner());
        ativo.setAlertaToner(leitura.alertaToner());
        ativo.setAlertaPapel(leitura.alertaPapel());
        ativo.setPapelVazio(leitura.papelVazio());
        ativo.setPapelQuaseVazio(leitura.papelQuaseVazio());

        if (contadorColetado != null) {
            ativo.setContadorTotal(contadorColetado);
            ativo.setContadorData(agora.format(FORMATO_DATA));
            ativo.setContadorErro(null);
            registrarSnapshot(ativo, contadorColetado, agora);
        } else {
            ativo.setContadorErro("Contador total não encontrado");
        }

        ativoRepository.save(ativo);
    }

    /** Grava um snapshot do contador, a menos que já exista um igual para este IP neste dia. */
    private void registrarSnapshot(Ativo ativo, long contador, LocalDateTime agora) {
        LocalDate hoje = agora.toLocalDate();
        if (historicoRepository.existsByIpAndDataDiaAndContador(ativo.getIp(), hoje, contador)) {
            return;
        }

        HistoricoContador historico = new HistoricoContador();
        historico.setIp(ativo.getIp());
        historico.setSetor(ativo.getSetor());
        historico.setMarca(ativo.getMarca());
        historico.setModelo(ativo.getModelo());
        historico.setSerie(ativo.getNumeroSerie());
        historico.setSite(ativo.getSite());
        historico.setContador(contador);
        historico.setDataHora(agora);
        historico.setDataDia(hoje);
        historicoRepository.save(historico);
    }

    /** Status do papel no formato que o histórico exibe. */
    private static String statusPapel(RicohWebService.LeituraRicoh leitura) {
        if (leitura == null) {
            return INDISPONIVEL;
        }
        if (Boolean.TRUE.equals(leitura.papelVazio())) {
            return "Sem papel";
        }
        if (Boolean.TRUE.equals(leitura.papelQuaseVazio())) {
            return "Pouco papel";
        }
        return "OK";
    }

    /**
     * Tipo exibido no histórico. Usa o valor enviado pelo frontend; se não houver,
     * calcula a partir do cadastro (mesma regra do index.html).
     */
    private static String descricaoTipo(RegistroTrocaRequest requisicao, Ativo ativo) {
        if (requisicao.descricaoTipo() != null && !requisicao.descricaoTipo().isBlank()) {
            return requisicao.descricaoTipo().trim().toUpperCase(Locale.ROOT);
        }
        String marcaTexto = (Objects.toString(ativo.getMarca(), "") + " "
                + Objects.toString(ativo.getTipo(), "")).toLowerCase(Locale.ROOT);
        if (marcaTexto.contains("epson")) {
            return "EPSON";
        }
        if (marcaTexto.contains("zebra")) {
            return "ZEBRA";
        }
        return Boolean.TRUE.equals(ativo.getShijibira()) ? "SHIJIBIRA" : "OFFICE";
    }

    private boolean isRicoh(Ativo ativo) {
        String texto = (Objects.toString(ativo.getMarca(), "") + " "
                + Objects.toString(ativo.getTipo(), "")).toLowerCase(Locale.ROOT);
        return texto.contains("ricoh");
    }

    /**
     * Converte o contador enviado pelo frontend. Aceita "1234" e "1.234".
     * Valores como "null", "N/A" ou "Indisponível" devolvem null.
     */
    private static Long parseContador(String texto) {
        if (texto == null) {
            return null;
        }
        String limpo = texto.trim().replaceAll("[.\\s]", "");
        if (!limpo.matches("\\d{1,12}")) {
            return null;
        }
        long valor = Long.parseLong(limpo);
        return valor > 0 ? valor : null;
    }
}
