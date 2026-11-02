package com.mcp.pikachu.wowlk.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.pikachu.wowlk.config.WowlkProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Cliente minimo da Abra API: fila (notifications) e e-mail em texto puro. */
@Slf4j
@Component
public class AbraClient {

    /** Mensagem da fila. {@code content} e o JSON do site em string (ou envelope cifrado). */
    public record QueueMessage(String id, String title, String key, String content, String dateCreated) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;

    public AbraClient(WowlkProperties props) {
        this.baseUrl = props.abraBaseUrl().replaceAll("/+$", "");
    }

    /** Lista as mensagens da chave. Vazio quando a Abra responde 404 (chave sem mensagens). */
    public List<QueueMessage> retrieve(String key) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/notifications/retrieve?key=" + enc(key)))
                .timeout(TIMEOUT).header("Cache-Control", "no-cache").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) return List.of();
        if (res.statusCode() / 100 != 2) throw new IOException("Abra retrieve HTTP " + res.statusCode());
        JsonNode arr = mapper.readTree(res.body());
        List<QueueMessage> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                out.add(new QueueMessage(text(n, "id"), text(n, "title"), text(n, "key"), text(n, "content"), text(n, "dateCreated")));
            }
        }
        return out;
    }

    /**
     * Apaga UMA mensagem pelo id. Concorrencia: o delete e sempre por id, entao mensagens que
     * chegarem durante o processamento nunca sao apagadas sem terem sido lidas.
     * 400/404 = ja nao existe (outro ciclo apagou) — tratado como sucesso (idempotente).
     */
    public void delete(String id, String key) throws IOException, InterruptedException {
        String body = mapper.writeValueAsString(Map.of("id", id, "key", key));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/notifications/delete"))
                .timeout(TIMEOUT).header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        int s = res.statusCode();
        if (s / 100 == 2 || s == 404 || s == 400) return;
        throw new IOException("Abra delete HTTP " + s);
    }

    /** E-mail simples, sem HTML: POST /email/send-email/{email}/{message}/{subject}. */
    public void sendTextEmail(String email, String subject, String message) throws IOException, InterruptedException {
        String url = baseUrl + "/email/send-email/" + encPath(email) + "/" + encPath(message) + "/" + encPath(subject);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IOException("Abra e-mail HTTP " + res.statusCode());
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Percent-encoding de segmento de path (URLEncoder usa '+' para espaco, que no path seria literal). */
    static String encPath(String s) {
        return enc(s).replace("+", "%20");
    }
}
