package com.prism.service;

import com.prism.config.PrismProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê o Web Image Monitor (WIM) de uma impressora Ricoh: toner, alertas de papel
 * e contador total de páginas.
 *
 * Cada leitura usa um cliente HTTP próprio, com cookies próprios. Assim, as sessões
 * de impressoras diferentes nunca se misturam, mesmo com coletas em paralelo.
 *
 * As senhas vêm de prism.impressoras.senha (variável IMP_SENHA) e nunca são registradas em log.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RicohWebService {

    /* ------------------------------------------------------------------
     * Caminhos do WIM. Confirme-os na sua versão de firmware, porque
     * podem variar entre modelos e idiomas (pt, br, en).
     * ------------------------------------------------------------------ */
    private static final String BASE_CGI = "/web/entry/pt/websys/webArch/";
    private static final String PAGINA_LOGIN = BASE_CGI + "auth.cgi";
    private static final String PAGINA_INICIAL = BASE_CGI + "mainframe.cgi";
    private static final List<String> PAGINAS_CONTADOR = List.of(
            BASE_CGI + "getUniteCounter.cgi",
            BASE_CGI + "getCounter.cgi",
            BASE_CGI + "getUniteCurrent.cgi",
            BASE_CGI + "configuration.cgi"
    );

    /** Nomes dos campos do formulário de login. Confirme-os no HTML da página de login. */
    private static final String CAMPO_USUARIO = "userid_work";
    private static final String CAMPO_SENHA = "password_work";

    /** Toner igual ou abaixo deste valor (%) gera alerta de toner crítico. */
    private static final int LIMITE_TONER_CRITICO = 15;

    private static final Duration TIMEOUT_CONEXAO = Duration.ofSeconds(5);
    private static final Duration TIMEOUT_REQUISICAO = Duration.ofSeconds(15);

    /** Captura "Preto: 80%", "Black 80 %", "Ciano 50%" etc. */
    private static final Pattern PADRAO_TONER = Pattern.compile(
            "(?i)(preto|black|ciano|cyan|magenta|amarelo|yellow)\\D{0,25}?(\\d{1,3})\\s*%");

    private final PrismProperties props;

    /** Contexto SSL compartilhado. É somente leitura depois de criado, então pode ser usado por várias threads. */
    private SSLContext contextoSsl;

    @PostConstruct
    void inicializar() throws Exception {
        if (props.impressoras().senha() == null || props.impressoras().senha().isBlank()) {
            log.warn("IMP_SENHA não definida. A leitura do Web Image Monitor das Ricoh ficará indisponível.");
        }
        contextoSsl = criarContextoSsl();
    }

    /**
     * Lê o estado de uma impressora Ricoh.
     *
     * @throws IllegalStateException se a senha não estiver configurada
     * @throws IOException           se a conexão, o login ou a leitura falharem
     */
    public LeituraRicoh ler(String ip) throws IOException, InterruptedException {
        if (props.impressoras().senha() == null || props.impressoras().senha().isBlank()) {
            throw new IllegalStateException("IMP_SENHA não definida");
        }

        String base = "https://" + ip;
        HttpClient client = criarCliente();

        autenticar(client, base);

        Document principal = baixar(client, base + PAGINA_INICIAL);
        List<Document> paginas = new ArrayList<>();
        paginas.add(principal);
        paginas.addAll(baixarFrames(client, base, principal));

        for (String caminho : PAGINAS_CONTADOR) {
            try {
                paginas.add(baixar(client, base + caminho));
            } catch (IOException e) {
                log.trace("Página {} indisponível em {}: {}", caminho, ip, e.getMessage());
            }
        }

        String texto = paginas.stream()
                .map(Document::text)
                .reduce("", (a, b) -> a + " " + b);

        String toner = extrairToner(texto);
        boolean papelVazio = contemAlguma(texto, "sem papel", "paper end");
        boolean papelQuaseVazio = contemAlguma(texto, "pouco papel", "paper near end");
        boolean alertaToner = tonerCritico(toner);

        Long contadorTotal = null;
        for (Document pagina : paginas) {
            Long candidato = extrairContadorTotal(pagina);
            if (candidato != null && (contadorTotal == null || candidato > contadorTotal)) {
                contadorTotal = candidato;
            }
        }

        return new LeituraRicoh(
                toner,
                alertaToner,
                papelVazio || papelQuaseVazio,
                papelVazio,
                papelQuaseVazio,
                contadorTotal
        );
    }

    /**
     * Abre a página de login para obter o cookie de sessão e envia usuário e senha.
     * Se a página inicial ainda exibir campo de senha após o envio, o login foi recusado.
     */
    private void autenticar(HttpClient client, String base) throws IOException, InterruptedException {
        // Primeiro acesso: obtém o cookie inicial.
        baixar(client, base + PAGINA_LOGIN);

        String corpo = CAMPO_USUARIO + "=" + codificar(props.impressoras().usuario())
                + "&" + CAMPO_SENHA + "=" + codificar(props.impressoras().senha());

        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(base + PAGINA_LOGIN))
                .timeout(TIMEOUT_REQUISICAO)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build();

        HttpResponse<byte[]> resposta = client.send(requisicao, HttpResponse.BodyHandlers.ofByteArray());
        if (resposta.statusCode() >= 400) {
            throw new IOException("Login recusado (HTTP " + resposta.statusCode() + ")");
        }

        Document verificacao = baixar(client, base + PAGINA_INICIAL);
        if (!verificacao.select("input[type=password]").isEmpty()) {
            throw new IOException("Login recusado pela impressora (credenciais inválidas)");
        }
    }

    /** Baixa os frames e iframes da página principal, aceitando apenas endereços do mesmo equipamento. */
    private List<Document> baixarFrames(HttpClient client, String base, Document principal)
            throws InterruptedException {
        List<Document> frames = new ArrayList<>();
        for (Element frame : principal.select("frame[src], iframe[src]")) {
            String url = frame.absUrl("src");
            if (url.isBlank() || !url.startsWith(base)) {
                continue;
            }
            try {
                frames.add(baixar(client, url));
            } catch (IOException e) {
                log.trace("Frame {} indisponível: {}", url, e.getMessage());
            }
        }
        return frames;
    }

    /** Baixa uma página e a converte em Document. O Jsoup detecta o charset pela própria página. */
    private Document baixar(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT_REQUISICAO)
                .GET()
                .build();

        HttpResponse<byte[]> resposta = client.send(requisicao, HttpResponse.BodyHandlers.ofByteArray());
        if (resposta.statusCode() >= 400) {
            throw new IOException("HTTP " + resposta.statusCode() + " em " + url);
        }
        return Jsoup.parse(new ByteArrayInputStream(resposta.body()), null, url);
    }

    /**
     * Procura níveis de toner no texto da página, no formato "Nome: NN%".
     * Devolve null quando nenhum nível for encontrado.
     */
    private String extrairToner(String texto) {
        Map<String, Integer> niveis = new LinkedHashMap<>();
        Matcher m = PADRAO_TONER.matcher(texto);

        while (m.find()) {
            int percentual = Integer.parseInt(m.group(2));
            if (percentual > 100) {
                continue;
            }
            String cor = nomeCor(m.group(1));
            niveis.putIfAbsent(cor, percentual);
        }

        if (niveis.isEmpty()) {
            return null;
        }

        List<String> partes = new ArrayList<>();
        niveis.forEach((cor, pct) -> partes.add(cor + ": " + pct + "%"));
        return String.join(" | ", partes);
    }

    /** Traduz o nome da cor para o português usado no frontend. */
    private static String nomeCor(String original) {
        return switch (original.toLowerCase(Locale.ROOT)) {
            case "preto", "black" -> "Preto";
            case "ciano", "cyan" -> "Ciano";
            case "magenta" -> "Magenta";
            case "amarelo", "yellow" -> "Amarelo";
            default -> original;
        };
    }

    /** Alerta de toner quando algum nível está no limite crítico ou abaixo dele. */
    private static boolean tonerCritico(String toner) {
        if (toner == null) {
            return false;
        }
        Matcher m = PADRAO_TONER.matcher(toner);
        while (m.find()) {
            if (Integer.parseInt(m.group(2)) <= LIMITE_TONER_CRITICO) {
                return true;
            }
        }
        return false;
    }

    private static boolean contemAlguma(String texto, String... trechos) {
        String minusculo = texto.toLowerCase(Locale.ROOT);
        for (String trecho : trechos) {
            if (minusculo.contains(trecho)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extrai o contador total de páginas. Considera apenas linhas cujo rótulo contém
     * "contador total" ou "total counter", para não confundir com contadores parciais.
     * Quando há mais de um valor, usa o maior.
     */
    private Long extrairContadorTotal(Document pagina) {
        Long melhor = null;

        for (Element linha : pagina.select("tr")) {
            String rotulo = linha.text().toLowerCase(Locale.ROOT);
            if (!rotulo.contains("contador total") && !rotulo.contains("total counter")) {
                continue;
            }
            for (Element celula : linha.select("td")) {
                Long valor = numero(celula.text());
                if (valor != null && (melhor == null || valor > melhor)) {
                    melhor = valor;
                }
            }
        }
        return melhor;
    }

    /** Converte "1.234.567" ou "1234567" em número. Devolve null para textos que não são só dígitos. */
    private static Long numero(String texto) {
        String limpo = texto.replaceAll("[.,\\s]", "");
        if (!limpo.matches("\\d{1,12}")) {
            return null;
        }
        long valor = Long.parseLong(limpo);
        return valor > 0 ? valor : null;
    }

    /** Cria um cliente novo para cada leitura, com cookies isolados. */
    private HttpClient criarCliente() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(TIMEOUT_CONEXAO)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .sslContext(contextoSsl)
                .build();
    }

    /**
     * Com ignorar-ssl=true, aceita certificados autoassinados das impressoras (rede interna).
     * Com ignorar-ssl=false, usa a validação padrão da JVM.
     */
    private SSLContext criarContextoSsl() throws Exception {
        if (!props.impressoras().ignorarSsl()) {
            return SSLContext.getDefault();
        }

        TrustManager[] confiaTudo = {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, confiaTudo, new SecureRandom());
        return ctx;
    }

    private static String codificar(String valor) {
        return URLEncoder.encode(valor == null ? "" : valor, StandardCharsets.UTF_8);
    }

    /**
     * Resultado de uma leitura do WIM. O nome dos campos é o mesmo usado pelo ColetorService.
     */
    public record LeituraRicoh(
            String toner,
            Boolean alertaToner,
            Boolean alertaPapel,
            Boolean papelVazio,
            Boolean papelQuaseVazio,
            Long contadorTotal
    ) {}
}
