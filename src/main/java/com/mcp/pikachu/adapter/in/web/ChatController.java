package com.mcp.pikachu.adapter.in.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.mcp.pikachu.domain.model.ChatRequest;
import com.mcp.pikachu.domain.port.in.Gemma3UseCase;
import com.mcp.pikachu.domain.port.in.Llama3UseCase;
import com.mcp.pikachu.domain.port.in.LlamaTinyUseCase;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin
@Tag(name = "Chat", description = "AI model endpoints")
public class ChatController {

    private final LlamaTinyUseCase llamaTinyUseCase;
    private final Llama3UseCase llama3UseCase;
    private final Gemma3UseCase gemma3UseCase;

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
}
