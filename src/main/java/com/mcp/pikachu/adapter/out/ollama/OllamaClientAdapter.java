package com.mcp.pikachu.adapter.out.ollama;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.pikachu.domain.exception.ModelUnavailableException;
import com.mcp.pikachu.domain.model.ChatRequest;
import com.mcp.pikachu.domain.port.out.LlmClientPort;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@Component
public class OllamaClientAdapter implements LlmClientPort {

    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final MediaType JSON = MediaType.parse("application/json");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String promptLlamaTiny(ChatRequest request) {
        log.info("Calling tinyllama via Ollama");
        return callOllama("tinyllama", request.prompt());
    }

    @Override
    public String llama3Response(ChatRequest request) {
        log.info("Calling llama3 via Ollama");
        String instruction = "PORTUGUESE".equalsIgnoreCase(request.language()) ? "Responder em português." : "Answer in English.";
        return callOllama("llama3", request.prompt() + instruction);
    }

    @Override
    public String getGemmaResponse(ChatRequest request) {
        log.info("Calling gemma3:12b via Ollama");
        String instruction = "PORTUGUESE".equalsIgnoreCase(request.language()) ? "Responder em português." : "Answer in English.";
        return callOllama("gemma3:12b", request.prompt() + instruction);
    }

    @Override
    public String qwen25Response(ChatRequest request) {
        log.info("Calling qwen2.5:7b-instruct via Ollama");
        String instruction = "PORTUGUESE".equalsIgnoreCase(request.language()) ? "Responder em português." : "Answer in English.";
        return callOllama("qwen2.5:7b-instruct", request.prompt() + instruction);
    }

    @Override
    public void llamaStreamResponse(ChatRequest request, HttpServletResponse response) throws IOException {
        log.info("Streaming llama3 response via Ollama");
        llama3StreamResponse(request, response);
    }

    @Override
    public void qwen36_17bStream(ChatRequest request, HttpServletResponse response) throws IOException {
        log.info("Streaming qwen3.6-17b (thinking) response via Ollama");
        qwen36ThinkingStreamResponse(request, response);
    }

    @Override
    public String callModel(String model, ChatRequest request) {
        log.info("Calling model [{}] via Ollama", model);
        String instruction = "PORTUGUESE".equalsIgnoreCase(request.language()) ? " Responder em português." : " Answer in English.";
        return callOllama(model, request.prompt() + instruction);
    }

    private String callOllama(String model, String prompt) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("stream", false);
            // De proposito SEM options.num_ctx aqui. O truncamento silencioso do
            // Ollama tambem acontece neste caminho, mas estes endpoints rodam
            // modelos de contexto curto (llama3 = 8k) e servem as respostas
            // rapidas de audio/print, onde o prompt e pequeno de todo jeito.
            // Pedir uma janela grande pra eles seria risco sem ganho.

            String requestBody = objectMapper.writeValueAsString(body);

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.MINUTES)
                    .build();

            Request httpRequest = new Request.Builder()
                    .url(OLLAMA_URL)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            try (Response response = client.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Ollama error: {} - {}", response.code(), response.message());
                    throw new IOException("Error in request: " + response.code() + " - " + response.message());
                }

                String responseBody = response.body().string();
                return objectMapper.readTree(responseBody).path("response").asText();
            }

        } catch (IOException e) {
            log.error("Failed to call Ollama [{}]: {}", model, e.getMessage());
            throw new ModelUnavailableException(model, e);
        }
    }

    private void llama3StreamResponse(ChatRequest request, HttpServletResponse response) throws IOException {

        // Flush automático no HTTP
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream");

        PrintWriter writer = response.getWriter();

        String instruction = "PORTUGUESE".equalsIgnoreCase(request.language()) ? " Responder em português." : " Answer in English.";
        String fullPrompt = request.prompt() + instruction;

        // Requisição
        Map<String, Object> requestBodyMap = new HashMap<>();
        requestBodyMap.put("model", "llama3");
        requestBodyMap.put("prompt", fullPrompt);
        requestBodyMap.put("stream", true);

        String requestBody = objectMapper.writeValueAsString(requestBodyMap);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build();

        Request httpRequest = new Request.Builder()
                .url("http://localhost:11434/api/generate")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .build();

        okhttp3.Call call = client.newCall(httpRequest);
        try (Response ollamaResponse = call.execute()) {
            if (!ollamaResponse.isSuccessful()) {
                writer.write("event: error\ndata: Request failed " + ollamaResponse.code() + "\n\n");
                writer.flush();
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(ollamaResponse.body().byteStream()));

            String line;
            StringBuilder fullResponse = new StringBuilder();
            String previousToken = null;
            boolean isFirstToken = true;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                // Cada linha do Ollama STREAM é um JSON
                JsonNode jsonNode = objectMapper.readTree(line);
                String token = jsonNode.path("response").asText();

                if (!token.isEmpty()) {
                    // ========== LÓGICA DE ESPAÇAMENTO FINAL ==========
                    String processedToken = token;

                    if (!isFirstToken && previousToken != null) {
                        boolean tokenStartsWithSpace = token.matches("^\\s.*");
                        boolean tokenIsPunctuation = token.matches("^[.,!?;:()\\[\\]`\"'\\-].*");
                        boolean prevEndedWithSpace = previousToken.matches(".*\\s$");
                        boolean prevWasPunctuation = previousToken.matches("^[`\"'(\\[]$");

                        // Detecção de sub-palavras (sufixos)
                        boolean tokenStartsWithLower = token.matches("^[a-zà-ÿ].*");
                        boolean prevEndsWithLower = previousToken.matches(".*[a-zà-ÿ]$");

                        // Palavras comuns PT-BR e EN-US que SEMPRE separam
                        boolean prevIsCommonWord = previousToken.toLowerCase()
                                .matches("a|o|e|é|da|do|de|em|um|uma|que|se|por|para|com|na|no|ou|as|os|the|of|in|on|at|to|for|with|by|from|is|are|was|were|be|or|and|but|if|it");

                        // Token ≤6 chars + ambos minúsculas + anterior NÃO é palavra comum
                        boolean likelyContinuation = tokenStartsWithLower &&
                                prevEndsWithLower &&
                                token.length() <= 6 &&
                                !prevIsCommonWord;

                        boolean needsSpace = !tokenStartsWithSpace &&
                                !tokenIsPunctuation &&
                                !prevEndedWithSpace &&
                                !prevWasPunctuation &&
                                !likelyContinuation;

                        if (needsSpace) {
                            processedToken = " " + token;
                        }
                    }

                    previousToken = token;
                    isFirstToken = false;
                    // ================================================

                    fullResponse.append(processedToken);

                    // Envia token para o frontend
                    String jsonToken = objectMapper.writeValueAsString(processedToken);
                    writer.write("data: {\"response\":" + jsonToken + "}\n\n");
                    writer.flush();
                }

                if (writer.checkError()) {
                    log.warn("Cliente desconectou durante stream llama3 — cancelando chamada ao Ollama.");
                    call.cancel();
                    return;
                }

                boolean done = jsonNode.path("done").asBoolean(false);
                if (done) break;
            }

            // Finaliza stream
            writer.write("event: end\ndata: done\n\n");
            writer.flush();
        }
    }

    private void qwen36ThinkingStreamResponse(ChatRequest request, HttpServletResponse response) throws IOException {

        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream");

        PrintWriter writer = response.getWriter();

        String instruction = "PORTUGUESE".equalsIgnoreCase(request.language()) ? " Responder em português." : " Answer in English.";
        String fullPrompt = request.prompt() + instruction;

        // Requisição: stream + think habilitam o envio incremental do raciocínio e da resposta
        Map<String, Object> requestBodyMap = new HashMap<>();
        requestBodyMap.put("model", "qwen3.6:27b");
        requestBodyMap.put("prompt", fullPrompt);
        requestBodyMap.put("stream", true);
        requestBodyMap.put("think", true);

        String requestBody = objectMapper.writeValueAsString(requestBodyMap);

        // Timeout de 10 minutos: este modelo é pesado e demorado
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
                .build();

        Request httpRequest = new Request.Builder()
                .url(OLLAMA_URL)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON))
                .build();

        okhttp3.Call call = client.newCall(httpRequest);
        try (Response ollamaResponse = call.execute()) {
            if (!ollamaResponse.isSuccessful()) {
                writer.write("event: error\ndata: Request failed " + ollamaResponse.code() + "\n\n");
                writer.flush();
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(ollamaResponse.body().byteStream()));

            String line;
            boolean thinkingPhase = false;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                // Cada linha do Ollama STREAM é um JSON
                JsonNode jsonNode = objectMapper.readTree(line);

                // Tokens do raciocínio (processo de "thinking" do modelo)
                String thinking = jsonNode.path("thinking").asText("");
                if (!thinking.isEmpty()) {
                    if (!thinkingPhase) {
                        // Sinaliza ao front o início do processo de raciocínio
                        writer.write("event: thinking-start\ndata: start\n\n");
                        thinkingPhase = true;
                    }
                    String jsonThinking = objectMapper.writeValueAsString(thinking);
                    writer.write("data: {\"thinking\":" + jsonThinking + "}\n\n");
                    writer.flush();
                }

                // Tokens da resposta final
                String token = jsonNode.path("response").asText("");
                if (!token.isEmpty()) {
                    if (thinkingPhase) {
                        // Encerra a fase de raciocínio antes de emitir a resposta
                        writer.write("event: thinking-end\ndata: done\n\n");
                        thinkingPhase = false;
                    }
                    String jsonToken = objectMapper.writeValueAsString(token);
                    writer.write("data: {\"response\":" + jsonToken + "}\n\n");
                    writer.flush();
                }

                if (writer.checkError()) {
                    log.warn("Cliente desconectou durante stream qwen3.6-17b — cancelando chamada ao Ollama.");
                    call.cancel();
                    return;
                }

                boolean done = jsonNode.path("done").asBoolean(false);
                if (done) break;
            }

            // Finaliza stream
            writer.write("event: end\ndata: done\n\n");
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to stream qwen3.6-17b: {}", e.getMessage());
            writer.write("event: error\ndata: " + e.getMessage() + "\n\n");
            writer.flush();
        }
    }

    // ─── Contexto (num_ctx) ──────────────────────────────────────────────────────
    // O Ollama nao aumenta a janela de contexto sozinho: se o prompt passa do
    // num_ctx, ele DESCARTA o excedente sem avisar. Como o tamanho do prompt aqui
    // varia de uma pergunta de audio (200 chars) ao prompt do modo IDE (dezenas de
    // milhares), o valor e calculado por request: pergunta curta continua barata e
    // rapida, prompt de agente recebe a janela que precisa, ate o teto.
    private static final int MIN_NUM_CTX = 4096;
    private static final int OUTPUT_HEADROOM_TOKENS = 2048;
    private static final double CHARS_PER_TOKEN = 3.0; // conservador (codigo + PT-BR)

    @org.springframework.beans.factory.annotation.Value("${app.ollama.max-num-ctx:32768}")
    private int maxNumCtx = 32768;

    private Map<String, Object> buildOptions(String prompt, String model) {
        Map<String, Object> options = new HashMap<>();
        options.put("num_ctx", resolveNumCtx(prompt, model));
        return options;
    }

    private int resolveNumCtx(String prompt, String model) {
        int cap = maxNumCtx > 0 ? maxNumCtx : 32768;
        int estimated = (int) Math.ceil((prompt == null ? 0 : prompt.length()) / CHARS_PER_TOKEN)
                + OUTPUT_HEADROOM_TOKENS;
        int ctx = MIN_NUM_CTX;
        while (ctx < estimated && ctx < cap) {
            ctx *= 2;
        }
        ctx = Math.min(ctx, cap);
        if (estimated > cap) {
            log.warn("Prompt para [{}] estimado em ~{} tokens, acima do teto de num_ctx ({}). "
                    + "O Ollama vai truncar — suba app.ollama.max-num-ctx se houver VRAM.",
                    model, estimated, cap);
        } else {
            log.info("num_ctx={} para [{}] (~{} tokens estimados)", ctx, model, estimated);
        }
        return ctx;
    }

    // Mantem a Call junto da Response para que quem esta lendo o stream possa
    // cancelar a chamada ao Ollama no meio (ver checkError() em genericStream) —
    // sem isso, um cliente que desconectou no meio da geracao deixava o Ollama
    // "preso" gerando ate o fim (ou ate o callTimeout de 10min), enfileirando
    // qualquer request nova atras dela num modelo pesado que so serve 1 por vez.
    private record CallAndResponse(okhttp3.Call call, Response response) {}

    private Request buildGenerateRequest(Map<String, Object> bodyMap) throws IOException {
        return new Request.Builder()
                .url(OLLAMA_URL)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(objectMapper.writeValueAsString(bodyMap), JSON))
                .build();
    }

    // Modelo sem suporte a raciocinio faz o Ollama devolver 400 quando think=true.
    // Em vez de estourar o erro na cara do usuario, tenta de novo sem o parametro.
    private CallAndResponse callGenerate(OkHttpClient client, Map<String, Object> bodyMap) throws IOException {
        okhttp3.Call call = client.newCall(buildGenerateRequest(bodyMap));
        Response response = call.execute();
        if (response.code() == 400 && bodyMap.containsKey("think")) {
            String detail = errorDetail(response);
            response.close();
            log.warn("Ollama recusou think=true ({}); repetindo sem raciocinio.", detail);
            Map<String, Object> retry = new HashMap<>(bodyMap);
            retry.remove("think");
            okhttp3.Call retryCall = client.newCall(buildGenerateRequest(retry));
            return new CallAndResponse(retryCall, retryCall.execute());
        }
        return new CallAndResponse(call, response);
    }

    private String errorDetail(Response response) {
        try {
            return response.body() == null ? "" : response.body().string().trim();
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public void genericStream(String model, ChatRequest request, HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream");
        // Proxy reverso (nginx/ngrok) que bufferiza a resposta transforma SSE em
        // "nada acontece por minutos e depois vem tudo junto".
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");

        PrintWriter writer = response.getWriter();

        String instruction = "PORTUGUESE".equalsIgnoreCase(request.language()) ? " Responder em português." : " Answer in English.";
        String fullPrompt = request.prompt() + instruction;

        Map<String, Object> requestBodyMap = new HashMap<>();
        requestBodyMap.put("model", model);
        requestBodyMap.put("prompt", fullPrompt);
        requestBodyMap.put("stream", true);
        requestBodyMap.put("think", true);
        // Sem isto o Ollama usa o num_ctx default (4096) e TRUNCA o prompt em
        // silencio. No modo IDE do helper-node o prompt leva a arvore do projeto,
        // a lista de ferramentas e os TOOL_RESULT acumulados: o que sobrava eram
        // as ultimas linhas, sem o bloco de ferramentas. O modelo entao raciocinava
        // "me disseram que tenho acesso ao diretorio, mas nao vejo ferramenta
        // nenhuma — isso e contraditorio" e entrava em loop no proprio raciocinio.
        requestBodyMap.put("options", buildOptions(fullPrompt, model));
        // Mantem o modelo carregado entre as iteracoes do tool loop (cada TOOL_CALL
        // e uma request nova; sem isto o modelo pode ser descarregado no meio).
        requestBodyMap.put("keep_alive", "30m");

        if (request.imageBase64() != null && !request.imageBase64().isEmpty()) {
            requestBodyMap.put("images", java.util.List.of(request.imageBase64()));
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
                .build();

        CallAndResponse cr = callGenerate(client, requestBodyMap);
        okhttp3.Call call = cr.call();
        try (Response ollamaResponse = cr.response()) {
            if (!ollamaResponse.isSuccessful()) {
                String detail = errorDetail(ollamaResponse);
                log.error("Ollama rejected [{}]: {} - {}", model, ollamaResponse.code(), detail);
                writer.write("event: error\ndata: Request failed " + ollamaResponse.code()
                        + (detail.isEmpty() ? "" : " - " + detail.replace('\n', ' ')) + "\n\n");
                writer.flush();
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(ollamaResponse.body().byteStream()));
            String line;
            boolean thinkingPhase = false;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                JsonNode jsonNode = objectMapper.readTree(line);

                String thinking = jsonNode.path("thinking").asText("");
                if (!thinking.isEmpty()) {
                    if (!thinkingPhase) {
                        writer.write("event: thinking-start\ndata: start\n\n");
                        thinkingPhase = true;
                    }
                    String jsonThinking = objectMapper.writeValueAsString(thinking);
                    writer.write("data: {\"thinking\":" + jsonThinking + "}\n\n");
                    writer.flush();
                }

                String token = jsonNode.path("response").asText("");
                if (!token.isEmpty()) {
                    if (token.contains("<think>")) {
                        thinkingPhase = true;
                        writer.write("event: thinking-start\ndata: start\n\n");
                        token = token.replace("<think>", "");
                    }

                    if (token.contains("</think>")) {
                        token = token.replace("</think>", "");
                        if (!token.isEmpty()) {
                            String jsonToken = objectMapper.writeValueAsString(token);
                            writer.write("data: {\"thinking\":" + jsonToken + "}\n\n");
                        }
                        writer.write("event: thinking-end\ndata: done\n\n");
                        writer.write("event: message\ndata: start\n\n"); // Reset event to message for subsequent text
                        thinkingPhase = false;
                        writer.flush();
                        continue; // Skip the regular response write for this chunk if we just ended thinking
                    }

                    if (thinkingPhase) {
                        String jsonToken = objectMapper.writeValueAsString(token);
                        writer.write("data: {\"thinking\":" + jsonToken + "}\n\n");
                    } else {
                        String jsonToken = objectMapper.writeValueAsString(token);
                        writer.write("data: {\"response\":" + jsonToken + "}\n\n");
                    }
                    writer.flush();
                }

                // PrintWriter NUNCA lanca IOException — engole a falha de escrita e so
                // marca um flag interno. Sem checar aqui, cliente desconectado (aborted
                // fetch do Node, app fechado) nao interrompia nada: o loop continuava
                // lendo do Ollama ate ele terminar sozinho, segurando o modelo (que so
                // atende 1 geracao pesada por vez) preso pra qualquer request nova.
                if (writer.checkError()) {
                    log.warn("Cliente desconectou durante stream de [{}] — cancelando chamada ao Ollama.", model);
                    call.cancel();
                    return;
                }

                boolean done = jsonNode.path("done").asBoolean(false);
                if (done) break;
            }

            writer.write("event: end\ndata: done\n\n");
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to stream {}: {}", model, e.getMessage());
            writer.write("event: error\ndata: " + e.getMessage() + "\n\n");
            writer.flush();
        }
    }

    @Override
    public String getAvailableModels() {
        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url("http://localhost:11434/api/tags")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            }
        } catch (IOException e) {
            log.error("Failed to fetch available models from Ollama", e);
        }
        return "{\"models\":[]}";
    }
}
