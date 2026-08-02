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
    // ESPACO RESERVADO PRA SAIDA. Era 2048 e essa era a causa de "o modelo
    // pensou e nao respondeu": o num_ctx e a menor potencia de 2 acima de
    // (prompt + headroom), entao com headroom pequeno o modelo SEMPRE terminava
    // com ~2 mil tokens de espaco pra gerar, qualquer que fosse a janela.
    // Um modelo com raciocinio gasta 4000-6000 tokens SO PENSANDO antes de
    // comecar a resposta (medido: 14048 chars = 4683 tokens de raciocinio num
    // turno real). Ele estourava a janela no meio do pensamento, o Ollama
    // parava, e o stream fechava com done:true e ZERO token de resposta.
    private static final int OUTPUT_HEADROOM_TOKENS = 8192;
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
            // Fase de raciocinio ABERTA no protocolo SSE (ja mandamos thinking-start).
            boolean thinkingOpen = false;
            // O raciocinio veio como <think>...</think> DENTRO do campo "response"
            // (modelo que embute o raciocinio no texto). Diferente do raciocinio
            // nativo do Ollama, que chega no campo "thinking" separado.
            boolean inlineThinking = false;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                JsonNode jsonNode = objectMapper.readTree(line);

                String thinking = jsonNode.path("thinking").asText("");
                if (!thinking.isEmpty()) {
                    if (!thinkingOpen) {
                        writer.write("event: thinking-start\ndata: start\n\n");
                        thinkingOpen = true;
                    }
                    String jsonThinking = objectMapper.writeValueAsString(thinking);
                    writer.write("data: {\"thinking\":" + jsonThinking + "}\n\n");
                    writer.flush();
                }

                String token = jsonNode.path("response").asText("");
                if (!token.isEmpty()) {
                    if (token.contains("<think>")) {
                        token = token.replace("<think>", "");
                        if (!thinkingOpen) {
                            writer.write("event: thinking-start\ndata: start\n\n");
                            thinkingOpen = true;
                        }
                        inlineThinking = true;
                    }

                    if (token.contains("</think>")) {
                        token = token.replace("</think>", "");
                        inlineThinking = false; // o que vier depois da tag e RESPOSTA
                    }

                    // AQUI ESTAVA O BUG QUE DEIXAVA A TELA VAZIA: com think=true o
                    // Ollama manda o raciocinio no campo "thinking" e a RESPOSTA no
                    // campo "response" — sem nenhuma tag </think> pra fechar a fase.
                    // Como a fase so fechava ao ver </think>, ela ficava aberta pra
                    // sempre e TODO token de resposta era reetiquetado como
                    // "thinking". O cliente jogava a resposta inteira no buffer de
                    // raciocinio, o buffer de resposta ficava vazio e o usuario via
                    // uma tela em branco, sem texto e sem erro.
                    // Regra correta: chegou "response" e nao estamos dentro de um
                    // <think> inline => o raciocinio acabou, fecha a fase.
                    if (thinkingOpen && !inlineThinking) {
                        writer.write("event: thinking-end\ndata: done\n\n");
                        writer.write("event: message\ndata: start\n\n");
                        thinkingOpen = false;
                    }

                    if (!token.isEmpty()) {
                        String jsonToken = objectMapper.writeValueAsString(token);
                        if (inlineThinking) {
                            writer.write("data: {\"thinking\":" + jsonToken + "}\n\n");
                        } else {
                            writer.write("data: {\"response\":" + jsonToken + "}\n\n");
                        }
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

            // Modelo que so raciocinou e nao produziu resposta: sem fechar a fase
            // aqui, o cliente fica com a caixa de raciocinio aberta pra sempre.
            if (thinkingOpen) {
                writer.write("event: thinking-end\ndata: done\n\n");
                thinkingOpen = false;
            }

            writer.write("event: end\ndata: done\n\n");
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to stream {}: {}", model, e.getMessage());
            writer.write("event: error\ndata: " + e.getMessage() + "\n\n");
            writer.flush();
        }
    }

    private static final String OLLAMA_CHAT_URL = "http://localhost:11434/api/chat";

    /**
     * MODO AGENTE — /api/chat com messages[] e tools[] nativos.
     *
     * Por que existe, em vez de reaproveitar o genericStream: la a conversa
     * inteira vira UMA string e a chamada de ferramenta e TEXTO que o modelo
     * escreve. Medido no fio: o modelo escrevia "TOOL_CALL" 13 vezes dentro do
     * proprio raciocinio, se convencia de que tinha executado, e a rodada
     * terminava sem emitir nada — pra ele "planejei" e "emiti" sao a mesma
     * coisa, as duas sao texto. Aqui a chamada volta como objeto tipado.
     *
     * Protocolo SSE de saida (mesma familia do /chat, mais um evento):
     *   event: thinking-start / data: {"thinking":"..."} / event: thinking-end
     *   event: message        / data: {"response":"..."}
     *   event: tool_call      / data: {"name":"...","arguments":{...}}
     *   event: end            / data: done
     */
    @Override
    public void agentStream(String model, com.mcp.pikachu.domain.model.AgentRequest request,
                            HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");

        PrintWriter writer = response.getWriter();

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", request.messages());
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", request.tools());
        }
        body.put("stream", true);
        body.put("think", true);
        body.put("keep_alive", "30m");
        // num_ctx dimensionado pelo tamanho REAL da conversa (todas as mensagens).
        int chars = 0;
        if (request.messages() != null) {
            for (Map<String, Object> m : request.messages()) {
                Object c = m.get("content");
                if (c != null) chars += String.valueOf(c).length();
            }
        }
        Map<String, Object> options = new HashMap<>();
        options.put("num_ctx", resolveNumCtx("x".repeat(Math.max(0, chars)), model));
        body.put("options", options);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
                .build();

        Request httpRequest = new Request.Builder()
                .url(OLLAMA_CHAT_URL)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON))
                .build();

        okhttp3.Call call = client.newCall(httpRequest);
        try (Response ollamaResponse = call.execute()) {
            if (!ollamaResponse.isSuccessful()) {
                String detail = errorDetail(ollamaResponse);
                log.error("Ollama /api/chat rejeitou [{}]: {} - {}", model, ollamaResponse.code(), detail);
                writer.write("event: error\ndata: Request failed " + ollamaResponse.code()
                        + (detail.isEmpty() ? "" : " - " + detail.replace('\n', ' ')) + "\n\n");
                writer.flush();
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(ollamaResponse.body().byteStream()));
            String line;
            boolean thinkingOpen = false;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = objectMapper.readTree(line);
                JsonNode message = node.path("message");

                String thinking = message.path("thinking").asText("");
                if (!thinking.isEmpty()) {
                    if (!thinkingOpen) {
                        writer.write("event: thinking-start\ndata: start\n\n");
                        thinkingOpen = true;
                    }
                    writer.write("data: {\"thinking\":" + objectMapper.writeValueAsString(thinking) + "}\n\n");
                    writer.flush();
                }

                // CHAMADA DE FERRAMENTA TIPADA — nao passa por texto nenhum.
                JsonNode toolCalls = message.path("tool_calls");
                if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                    if (thinkingOpen) {
                        writer.write("event: thinking-end\ndata: done\n\n");
                        thinkingOpen = false;
                    }
                    for (JsonNode tc : toolCalls) {
                        JsonNode fn = tc.path("function");
                        Map<String, Object> saida = new HashMap<>();
                        saida.put("name", fn.path("name").asText(""));
                        saida.put("arguments", objectMapper.convertValue(fn.path("arguments"), Object.class));
                        writer.write("event: tool_call\ndata: " + objectMapper.writeValueAsString(saida) + "\n\n");
                    }
                    writer.flush();
                }

                String content = message.path("content").asText("");
                if (!content.isEmpty()) {
                    if (thinkingOpen) {
                        writer.write("event: thinking-end\ndata: done\n\n");
                        writer.write("event: message\ndata: start\n\n");
                        thinkingOpen = false;
                    }
                    writer.write("data: {\"response\":" + objectMapper.writeValueAsString(content) + "}\n\n");
                    writer.flush();
                }

                if (writer.checkError()) {
                    log.warn("Cliente desconectou durante agentStream [{}] — cancelando chamada ao Ollama.", model);
                    call.cancel();
                    return;
                }

                if (node.path("done").asBoolean(false)) break;
            }

            if (thinkingOpen) {
                writer.write("event: thinking-end\ndata: done\n\n");
            }
            writer.write("event: end\ndata: done\n\n");
            writer.flush();
        } catch (IOException e) {
            log.error("Falha no agentStream {}: {}", model, e.getMessage());
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
