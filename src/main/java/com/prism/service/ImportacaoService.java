package com.prism.service;

import com.prism.config.PrismProperties;
import com.prism.model.Ativo;
import com.prism.repository.AtivoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row.MissingCellPolicy;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Importa a lista de equipamentos a partir da planilha (ativos.xlsx).
 *
 * A coluna é localizada pelo nome do cabeçalho, sem depender da posição.
 * Os nomes são normalizados (sem acento, minúsculos, sem espaços).
 *
 * A importação é um upsert pelo IP: equipamentos novos são criados e os
 * existentes têm os dados cadastrais atualizados. Os campos da coleta
 * (online, toner, contador, alertas) não são alterados aqui.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportacaoService {

    /** IPs da linha de produção, marcados como Shijibira e críticos. Mesma lista do index.html. */
    private static final Set<String> IPS_SHIJIBIRA = Set.of(
            "172.27.32.72", "172.27.32.75", "172.27.32.76", "172.27.32.77",
            "172.27.32.74", "172.27.32.214", "172.27.32.209", "172.27.32.70",
            "172.27.32.71", "172.27.32.73", "172.27.22.5", "172.27.40.68",
            "172.27.32.220", "172.27.24.33", "172.27.32.219", "172.27.27.5",
            "172.27.32.243"
    );

    /** Nome interno do campo -> cabeçalhos aceitos na planilha (já normalizados). */
    private static final Map<String, List<String>> CABECALHOS = Map.of(
            "setor", List.of("setor"),
            "local", List.of("local", "localizacao"),
            "marca", List.of("marca", "fabricante"),
            "modelo", List.of("modelo"),
            "numeroSerie", List.of("numeroserie", "serie", "nserie", "numerodeserie"),
            "ip", List.of("ip", "enderecoip"),
            "imp", List.of("imp", "codigoimp"),
            "site", List.of("site", "planta"),
            "tipo", List.of("tipo"),
            "versao", List.of("versao", "firmware")
    );

    private final AtivoRepository ativoRepository;
    private final PrismProperties props;

    /**
     * Lê a planilha configurada em prism.importacao.planilha e grava os equipamentos.
     * Se o arquivo não existir, registra um aviso e não interrompe a aplicação.
     */
    @Transactional
    public ResultadoImportacao importarPlanilha() {
        Path caminho = Path.of(props.importacao().planilha());

        if (!Files.exists(caminho)) {
            log.warn("Planilha não encontrada em {}. Importação ignorada.", caminho.toAbsolutePath());
            return new ResultadoImportacao(0, 0, 0);
        }

        int criados = 0;
        int atualizados = 0;
        int ignorados = 0;
        DataFormatter formatter = new DataFormatter();

        try (InputStream in = Files.newInputStream(caminho);
             Workbook workbook = WorkbookFactory.create(in)) {

            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> colunas = mapearColunas(sheet, formatter);

            if (!colunas.containsKey("ip")) {
                throw new IllegalStateException(
                        "A planilha " + caminho + " precisa de uma coluna 'IP' no cabeçalho.");
            }

            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                String ip = valor(row, colunas, "ip", formatter);
                if (ip == null) {
                    ignorados++;
                    continue;
                }

                Optional<Ativo> existente = ativoRepository.findByIp(ip);
                Ativo ativo = existente.orElseGet(Ativo::new);
                preencher(ativo, row, colunas, formatter, ip);
                ativoRepository.save(ativo);

                if (existente.isPresent()) {
                    atualizados++;
                } else {
                    criados++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler a planilha " + caminho, e);
        }

        log.info("Importação concluída: {} criados, {} atualizados, {} linhas ignoradas.",
                criados, atualizados, ignorados);

        return new ResultadoImportacao(criados, atualizados, ignorados);
    }

    /** Relaciona cada campo interno à posição da coluna na planilha. */
    private Map<String, Integer> mapearColunas(Sheet sheet, DataFormatter formatter) {
        Map<String, Integer> colunas = new HashMap<>();
        Row cabecalho = sheet.getRow(sheet.getFirstRowNum());
        if (cabecalho == null) {
            return colunas;
        }

        for (int c = 0; c < cabecalho.getLastCellNum(); c++) {
            String titulo = normalizar(formatter.formatCellValue(cabecalho.getCell(c)));
            if (titulo.isEmpty()) {
                continue;
            }

            for (Map.Entry<String, List<String>> campo : CABECALHOS.entrySet()) {
                if (campo.getValue().contains(titulo) && !colunas.containsKey(campo.getKey())) {
                    colunas.put(campo.getKey(), c);
                }
            }
        }
        return colunas;
    }

    /** Copia os dados cadastrais da linha para a entidade. */
    private void preencher(Ativo ativo, Row row, Map<String, Integer> colunas,
                           DataFormatter formatter, String ip) {
        ativo.setIp(ip);
        ativo.setSetor(valor(row, colunas, "setor", formatter));
        ativo.setLocal(valor(row, colunas, "local", formatter));
        ativo.setMarca(valor(row, colunas, "marca", formatter));
        ativo.setModelo(valor(row, colunas, "modelo", formatter));
        ativo.setNumeroSerie(valor(row, colunas, "numeroSerie", formatter));
        ativo.setImp(valor(row, colunas, "imp", formatter));
        ativo.setSite(valor(row, colunas, "site", formatter));
        ativo.setTipo(valor(row, colunas, "tipo", formatter));
        ativo.setVersao(valor(row, colunas, "versao", formatter));

        boolean shijibira = IPS_SHIJIBIRA.contains(ip);
        ativo.setShijibira(shijibira);
        ativo.setCritica(shijibira);
    }

    /** Devolve o texto da célula, ou null se a coluna não existir ou a célula estiver vazia. */
    private String valor(Row row, Map<String, Integer> colunas, String campo, DataFormatter formatter) {
        Integer indice = colunas.get(campo);
        if (indice == null) {
            return null;
        }

        Cell cell = row.getCell(indice, MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            return null;
        }

        String texto = formatter.formatCellValue(cell).trim();
        return texto.isEmpty() ? null : texto;
    }

    /** Remove acentos, espaços e símbolos, e converte para minúsculas. */
    private static String normalizar(String texto) {
        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Resumo da importação, usado pelo DataLoader e pelos logs. */
    public record ResultadoImportacao(int criados, int atualizados, int ignorados) {}
}
