package com.mcp.pikachu.adapter.in.web;

import com.mcp.pikachu.domain.port.in.*;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.mcp.pikachu.domain.model.ChatRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin
@Tag(name = "Chat", description = "AI model endpoints")
public class ChatController {

    private final LlamaTinyUseCase llamaTinyUseCase;
    private final Llama3UseCase llama3UseCase;
    private final Gemma3UseCase gemma3UseCase;
    private final Qwen25UseCase qwen25UseCase;
    private final LlamaStreamUseCase llamaStreamUseCase;
    private final Qwen36_17bUseCase qwen36_17bUseCase;

    @Operation(summary = "Generate text with TinyLlama")
    @PostMapping("/llamatiny")
    public ResponseEntity<String> llamaTiny(@RequestBody ChatRequest request) {
        log.info("Received llamatiny request");
        return ResponseEntity.ok(llamaTinyUseCase.execute(request));
    }

    @Operation(summary = "Generate text with Llama3")
    @PostMapping("/llama3")
    public ResponseEntity<String> llama3(@RequestBody ChatRequest request) {
        log.info("Received llama3 request");
        return ResponseEntity.ok(llama3UseCase.execute(request));
    }

    @Operation(summary = "Generate text with Gemma3")
    @PostMapping("/gemma3")
    public ResponseEntity<String> gemma3(@RequestBody ChatRequest request) {
        log.info("Received gemma3 request");
        return ResponseEntity.ok(gemma3UseCase.execute(request));
    }

    @Operation(summary = "Generate text with Qwen2.5 7B Instruct")
    @PostMapping("/qwen25")
    public ResponseEntity<String> qwen25(@RequestBody ChatRequest request) {
        log.info("Received qwen2.5 request");
        return ResponseEntity.ok(qwen25UseCase.execute(request));
    }

    @PostMapping(value = "/llama3-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamLlama3Response(
            @RequestBody ChatRequest request,
            HttpServletResponse response
    ) throws IOException {
        llamaStreamUseCase.llamaStream(request, response);
    }

    @Operation(summary = "Heavy generating text with Qwen3.6 17B, streaming the model's thinking process then the answer")
    @PostMapping(value = "/qwen3.6-17b", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void qwen36(@RequestBody ChatRequest request,
                       jakarta.servlet.http.HttpServletRequest servletRequest,
                       HttpServletResponse response) throws IOException {
        log.info("Received qwen3.6 17B stream request");
        AsyncContext asyncContext = servletRequest.startAsync();
        asyncContext.setTimeout(600_000L); // 10 minutos
        qwen36_17bUseCase.execute(request, response);
    }
}
