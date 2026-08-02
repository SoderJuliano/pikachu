package com.mcp.pikachu.adapter.in.web;

import com.mcp.pikachu.domain.model.ChatRequest;
import com.mcp.pikachu.domain.port.in.GenericChatUseCase;
import com.mcp.pikachu.domain.port.in.InstallModelUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import java.io.IOException;

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin
@Tag(name = "Models", description = "Ollama model management and generic inference")
public class ModelController {

    private final InstallModelUseCase installModelUseCase;
    private final GenericChatUseCase genericChatUseCase;
    private final com.mcp.pikachu.domain.port.out.LlmClientPort llmClientPort;

    @Operation(summary = "List available models from local Ollama")
    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getModels() {
        log.info("Received request to list available models");
        return ResponseEntity.ok(llmClientPort.getAvailableModels());
    }

    @Operation(summary = "Install a new Ollama model by name (e.g. llama3.2, mistral)")
    @PostMapping("/models/install")
    public ResponseEntity<String> installModel(@RequestParam String model) {
        log.info("Received install request for model: {}", model);
        return ResponseEntity.ok(installModelUseCase.execute(model));
    }

    @Operation(summary = "Modo agente: conversa com papéis + tool calling NATIVO (/api/chat do Ollama)")
    @PostMapping(value = "/agent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void agent(@RequestParam String model,
                      @RequestBody com.mcp.pikachu.domain.model.AgentRequest request,
                      HttpServletRequest servletRequest, HttpServletResponse response) throws IOException {
        log.info("Agent request for model: {} ({} mensagens, {} ferramentas)", model,
                request.messages() == null ? 0 : request.messages().size(),
                request.tools() == null ? 0 : request.tools().size());
        AsyncContext asyncContext = servletRequest.startAsync();
        asyncContext.setTimeout(600_000L);
        try {
            llmClientPort.agentStream(model, request, response);
        } finally {
            asyncContext.complete();
        }
    }

    @Operation(summary = "Send a prompt to any locally available Ollama model via ?model=<name>")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void chat(@RequestParam String model, @RequestBody ChatRequest request,
                     HttpServletRequest servletRequest, HttpServletResponse response) throws IOException {
        log.info("Received generic chat request for model: {}", model);
        AsyncContext asyncContext = servletRequest.startAsync();
        asyncContext.setTimeout(600_000L); // 10 minutes
        try {
            genericChatUseCase.executeStream(model, request, response);
        } finally {
            // Sem isto o AsyncContext ficava "aberto" ate o timeout de 10min mesmo
            // apos o stream terminar de verdade — o container so libera o slot
            // async no complete() ou no timeout, nunca so por o metodo retornar.
            asyncContext.complete();
        }
    }
}

