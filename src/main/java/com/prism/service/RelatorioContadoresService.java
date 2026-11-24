package com.prism.service;

import com.prism.dto.RelatorioContadoresDTO;
import com.prism.dto.RelatorioContadoresDTO.Detalhe;
import com.prism.dto.RelatorioContadoresDTO.Kpis;
import com.prism.dto.RelatorioContadoresDTO.PorData;
import com.prism.dto.RelatorioContadoresDTO.PorSetor;
import com.prism.model.HistoricoContador;
import com.prism.repository.HistoricoContadorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * Monta o BI de contadores (GET /api/relatorio-contadores).
 *
 * Regras:
 *   - Para cada equipamento, o delta do período é max(0, contador final - contador inicial),
 *     usando a primeira e a última leitura dentro do filtro de datas.
 *   - O volume por dia soma as diferenças entre leituras consecutivas, atribuídas ao dia da leitura mais recente.
 *   - Período sem data inicial ou final usa limites amplos (1970 até 2999).
 */
@Service
@RequiredArgsConstructor
public class RelatorioContadoresService {

    private static final LocalDate INICIO_PADRAO = LocalDate.of(1970, 1, 1);
    private static final LocalDate FIM_PADRAO = LocalDate.of(2999, 12, 31);
    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FORMATO_DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final String SEM_SETOR = "Sem setor";

    private final HistoricoContadorRepository historicoRepository;

    /**
     * Gera o relatório para o período e filtros informados. Filtros nulos são ignorados.
     *
     * @throws IllegalArgumentException se a data inicial for posterior à data final
     */
    @Transactional(readOnly = true)
    public RelatorioContadoresDTO gerar(LocalDate dataInicio, LocalDate dataFim,
                                        String setor, String marca, String planta) {
        LocalDate ini = dataInicio != null ? dataInicio : INICIO_PADRAO;
        LocalDate fim = dataFim != null ? dataFim : FIM_PADRAO;
        if (ini.isAfter(fim)) {
            throw new IllegalArgumentException("A data inicial não pode ser maior que a data final.");
        }

        List<HistoricoContador> leituras = historicoRepository.buscarPorPeriodo(
                ini.atStartOfDay(),
                fim.atTime(LocalTime.MAX),
                textoFiltro(setor),
                textoFiltro(marca),
                textoFiltro(planta));

        // A consulta já vem ordenada por IP e data, então cada lista de IP fica em ordem cronológica.
        Map<String, List<HistoricoContador>> porIp = leituras.stream()
                .collect(Collectors.groupingBy(HistoricoContador::getIp, LinkedHashMap::new, Collectors.toList()));

        List<Detalhe> detalhes = new ArrayList<>();
        Map<LocalDate, Long> paginasPorDia = new TreeMap<>();

        for (List<HistoricoContador> serie : porIp.values()) {
            HistoricoContador primeira = serie.get(0);
            HistoricoContador ultima = serie.get(serie.size() - 1);

            long contadorInicial = primeira.getContador();
            long contadorFinal = ultima.getContador();
            long delta = Math.max(0L, contadorFinal - contadorInicial);

            detalhes.add(new Detalhe(
                    nomeSetor(ultima.getSetor()),
                    ultima.getIp(),
                    ultima.getModelo(),
                    contadorInicial,
                    contadorFinal,
                    delta,
                    ultima.getDataHora().format(FORMATO_DATA_HORA)));

            acumularPorDia(serie, paginasPorDia);
        }

        detalhes.sort(Comparator.comparing(Detalhe::setor).thenComparing(Detalhe::ip));

        List<PorSetor> porSetor = agruparPorSetor(detalhes);
        List<PorData> porData = paginasPorDia.entrySet().stream()
                .map(e -> new PorData(e.getKey().format(FORMATO_DATA), e.getValue()))
                .toList();

        long totalPaginas = somar(detalhes, Detalhe::paginasPeriodo);
        int avaliados = detalhes.size();
        long media = avaliados > 0 ? totalPaginas / avaliados : 0L;
        String setorMaior = (porSetor.isEmpty() || porSetor.get(0).paginasPeriodo() == 0)
                ? null
                : porSetor.get(0).setor();

        Kpis kpis = new Kpis(totalPaginas, avaliados, media, setorMaior);
        return new RelatorioContadoresDTO(kpis, porSetor, porData, detalhes);
    }

    /** Agrupa os equipamentos por setor e ordena do setor com maior volume para o menor. */
    private static List<PorSetor> agruparPorSetor(List<Detalhe> detalhes) {
        Map<String, List<Detalhe>> porSetor = detalhes.stream()
                .collect(Collectors.groupingBy(Detalhe::setor, LinkedHashMap::new, Collectors.toList()));

        return porSetor.entrySet().stream()
                .map(e -> new PorSetor(
                        e.getKey(),
                        e.getValue().size(),
                        somar(e.getValue(), Detalhe::paginasPeriodo),
                        somar(e.getValue(), Detalhe::contadorFinal)))
                .sorted(Comparator.comparingLong(PorSetor::paginasPeriodo).reversed()
                        .thenComparing(PorSetor::setor))
                .toList();
    }

    /** Soma as diferenças entre leituras consecutivas de um mesmo IP, no dia da leitura mais recente. */
    private static void acumularPorDia(List<HistoricoContador> serie, Map<LocalDate, Long> paginasPorDia) {
        for (int i = 1; i < serie.size(); i++) {
            HistoricoContador anterior = serie.get(i - 1);
            HistoricoContador atual = serie.get(i);
            long diferenca = Math.max(0L, atual.getContador() - anterior.getContador());
            paginasPorDia.merge(atual.getDataDia(), diferenca, Long::sum);
        }
    }

    private static long somar(List<Detalhe> lista, ToLongFunction<Detalhe> campo) {
        return lista.stream().mapToLong(campo).sum();
    }

    /** Filtro vazio vira "" para a consulta JPQL, que não aceita parâmetro nulo nas comparações. */
    private static String textoFiltro(String valor) {
        return valor == null ? "" : valor.trim().toLowerCase(Locale.ROOT);
    }

    private static String nomeSetor(String setor) {
        return (setor == null || setor.isBlank()) ? SEM_SETOR : setor.trim();
    }
}
