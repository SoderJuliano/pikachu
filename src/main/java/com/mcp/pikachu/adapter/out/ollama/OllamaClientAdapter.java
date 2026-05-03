package com.mcp.pikachu.adapter.out.ollama;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
        log.info("Calling gemma3 via Ollama");
        String instruction = "PORTUGUESE".equalsIgnoreCase(request.language()) ? "Responder em português." : "Answer in English.";
        return callOllama("gemma3-4b", request.prompt() + instruction);
    }

    private String callOllama(String model, String prompt) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("stream", false);

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
}
