package com.prism.config;

import com.prism.repository.AtivoRepository;
import com.prism.service.ImportacaoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Executa a importação inicial da planilha na primeira inicialização da aplicação.
 *
 * A importação só acontece quando a tabela "ativos" está vazia. Assim, reiniciar
 * o sistema não sobrescreve os dados já cadastrados.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataLoader implements ApplicationRunner {

    private final AtivoRepository ativoRepository;
    private final ImportacaoService importacaoService;
    private final PrismProperties props;

    @Override
    public void run(ApplicationArguments args) {
        if (!props.importacao().habilitada()) {
            log.info("Importação inicial desabilitada (prism.importacao.habilitada=false).");
            return;
        }

        if (ativoRepository.count() > 0) {
            log.info("Tabela 'ativos' já possui registros. Importação inicial ignorada.");
            return;
        }

        log.info("Tabela 'ativos' vazia. Iniciando importação da planilha...");
        ImportacaoService.ResultadoImportacao resultado = importacaoService.importarPlanilha();
        log.info("Importação inicial: {} criados, {} linhas ignoradas.",
                resultado.criados(), resultado.ignorados());
    }
}
