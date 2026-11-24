package com.prism.service;

import com.prism.config.PrismProperties;
import com.prism.model.Ativo;
import com.prism.model.HistoricoContador;
import com.prism.repository.AtivoRepository;
import com.prism.repository.HistoricoContadorRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coleta o estado dos equipamentos: conectividade (ping) e, nas Ricoh online,
 * níveis de toner, alertas de papel e contador total de páginas.
 *
 * Fluxo de uma coleta:
 *   1. Carrega todos os ativos do banco.
 *   2. Executa, em paralelo, a verificação de cada equipamento.
 *   3. Aplica os resultados na thread principal e grava tudo em uma única chamada ao banco.
 *
 * As tarefas paralelas só leem dados. Quem altera entidades e grava no banco é a thread principal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ColetorService {

    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    /** Tempo máximo, em segundos, para uma única verificação terminar. */
    private static final long TIMEOUT_TAREFA_SEG = 120;

    private final AtivoRepository ativoRepository;
    private final HistoricoContadorRepository historicoRepository;
    private final RicohWebService ricohWebService;
    private final PrismProperties props;

    /** Impede duas coletas rodando ao mesmo tempo (automática e manual, por exemplo). */
    private final AtomicBoolean emExecucao = new AtomicBoolean(false);

    private ExecutorService executor;

    @PostConstruct
    void iniciarPool() {
        executor = Executors.newFixedThreadPool(props.coleta().threads());
    }

    @PreDestroy
    void encerrarPool() {
        executor.shutdownNow();
    }

    /** Coleta automática, no intervalo definido em prism.coleta.intervalo-ms. */
    @Scheduled(fixedDelayString = "${prism.coleta.intervalo-ms:120000}",
               initialDelayString = "${prism.coleta.inicial-ms:15000}")
    public void coletarAutomatico() {
        try {
            int total = coletarAgora();
            log.info("Coleta automática concluída: {} equipamentos.", total);
        } catch (IllegalStateException e) {
            log.warn("Coleta automática ignorada: {}", e.getMessage());
        }
    }

    /**
     * Executa uma coleta completa e devolve a quantidade de equipamentos verificados.
     *
     * @throws IllegalStateException se outra coleta já estiver em andamento
     */
    public int coletarAgora() {
        if (!emExecucao.compareAndSet(false, true)) {
            throw new IllegalStateException("Já existe uma coleta em andamento.");
        }

        try {
            long inicio = System.currentTimeMillis();
            List<Ativo> ativos = ativoRepository.findAll();

            List<Future<ResultadoColeta>> futuros = new ArrayList<>(ativos.size());
            for (Ativo ativo : ativos) {
                futuros.add(executor.submit(() -> coletar(ativo)));
            }

            LocalDateTime agora = LocalDateTime.now();
            for (int i = 0; i < ativos.size(); i++) {
                Ativo ativo = ativos.get(i);
                ResultadoColeta resultado = aguardar(futuros.get(i));
                aplicarResultado(ativo, resultado, agora);
            }

            ativoRepository.saveAll(ativos);
            log.info("Coleta de {} equipamentos concluída em {} ms.",
                    ativos.size(), System.currentTimeMillis() - inicio);
            return ativos.size();
        } finally {
            emExecucao.set(false);
        }
    }

    /** Verificação de um único equipamento. Roda na thread do pool. */
    private ResultadoColeta coletar(Ativo ativo) {
        boolean online = verificarConectividade(ativo.getIp());
        if (!online || !isRicoh(ativo)) {
            return new ResultadoColeta(online, null, null);
        }

        try {
            return new ResultadoColeta(true, ricohWebService.ler(ativo.getIp()), null);
        } catch (Exception e) {
            log.debug("Falha ao ler o Web Image Monitor de {}: {}", ativo.getIp(), e.getMessage());
            return new ResultadoColeta(true, null, mensagemErro(e));
        }
    }

    /** Espera o resultado de uma tarefa. Em caso de falha ou tempo esgotado, devolve um resultado sem status. */
    private ResultadoColeta aguardar(Future<ResultadoColeta> futuro) {
        try {
            return futuro.get(TIMEOUT_TAREFA_SEG, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            futuro.cancel(true);
            return ResultadoColeta.falha("Tempo esgotado na coleta");
        } catch (ExecutionException e) {
            return ResultadoColeta.falha("Erro na coleta: " + mensagemErro(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResultadoColeta.falha("Coleta interrompida");
        }
    }

    /**
     * Aplica o resultado da coleta ao equipamento.
     * Quando o resultado não tem status (erro ou tempo esgotado), o status anterior é mantido.
     */
    private void aplicarResultado(Ativo ativo, ResultadoColeta resultado, LocalDateTime agora) {
        if (resultado.online() != null) {
            ativo.setOnline(resultado.online());
            ativo.setUltimaVerificacao(agora);
        }

        if (!Boolean.TRUE.equals(ativo.getOnline())) {
            return;
        }

        if (resultado.leitura() == null) {
            if (resultado.erro() != null) {
                ativo.setContadorErro(resultado.erro());
            }
            return;
        }

        RicohWebService.LeituraRicoh leitura = resultado.leitura();
        ativo.setToner(leitura.toner());
        ativo.setAlertaToner(leitura.alertaToner());
        ativo.setAlertaPapel(leitura.alertaPapel());
        ativo.setPapelVazio(leitura.papelVazio());
        ativo.setPapelQuaseVazio(leitura.papelQuaseVazio());

        if (leitura.contadorTotal() != null) {
            ativo.setContadorTotal(leitura.contadorTotal());
            ativo.setContadorData(agora.format(FORMATO_DATA));
            ativo.setContadorErro(null);
            registrarSnapshot(ativo, leitura.contadorTotal(), agora);
        } else {
            ativo.setContadorErro("Contador total não encontrado");
        }
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

    /**
     * Testa se o IP responde. Tenta primeiro o ping Java (ICMP) e depois o comando ping do sistema.
     */
    private boolean verificarConectividade(String ip) {
        int timeoutMs = props.coleta().pingTimeoutMs();
        try {
            if (InetAddress.getByName(ip).isReachable(timeoutMs)) {
                return true;
            }
        } catch (IOException e) {
            log.trace("Ping Java falhou para {}: {}", ip, e.getMessage());
        }
        return pingDoSistema(ip, timeoutMs);
    }

    /**
     * Executa o comando ping do sistema. Usa lista de argumentos, não uma string,
     * para que o IP nunca seja interpretado pelo shell.
     */
    private boolean pingDoSistema(String ip, int timeoutMs) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> comando = windows
                ? List.of("ping", "-n", "1", "-w", String.valueOf(timeoutMs), ip)
                : List.of("ping", "-c", "1", "-W", String.valueOf(Math.max(1, timeoutMs / 1000)), ip);

        try {
            Process processo = new ProcessBuilder(comando).redirectErrorStream(true).start();

            if (!processo.waitFor(timeoutMs + 2000L, TimeUnit.MILLISECONDS)) {
                processo.destroyForcibly();
                return false;
            }

            String saida = new String(processo.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1)
                    .toLowerCase(Locale.ROOT);

            // O código de saída sozinho não basta: em alguns casos o Windows devolve 0 mesmo sem resposta.
            boolean respondeu = saida.contains("ttl=")
                    || saida.contains("1 received")
                    || saida.contains("1 packets received");

            return processo.exitValue() == 0 && respondeu;
        } catch (IOException e) {
            log.warn("Comando ping indisponível no sistema: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Mesma regra do index.html: marca ou tipo contendo "ricoh". */
    private boolean isRicoh(Ativo ativo) {
        String texto = (Objects.toString(ativo.getMarca(), "") + " " + Objects.toString(ativo.getTipo(), ""))
                .toLowerCase(Locale.ROOT);
        return texto.contains("ricoh");
    }

    private static String mensagemErro(Throwable e) {
        if (e == null) {
            return "erro desconhecido";
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /**
     * Resultado de uma verificação.
     * online: true/false quando o ping respondeu; null quando não houve resposta (erro ou tempo esgotado).
     */
    private record ResultadoColeta(Boolean online, RicohWebService.LeituraRicoh leitura, String erro) {

        static ResultadoColeta falha(String erro) {
            return new ResultadoColeta(null, null, erro);
        }
    }
}
