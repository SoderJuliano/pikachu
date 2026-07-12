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

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin
@Tag(name = "Models", description = "Ollama model management and generic inference")
public class ModelController {

    private final InstallModelUseCase installModelUseCase;
    private final GenericChatUseCase genericChatUseCase;

    @Operation(summary = "Install a new Ollama model by name (e.g. llama3.2, mistral)")
    @PostMapping("/models/install")
    public ResponseEntity<String> installModel(@RequestParam String model) {
        log.info("Received install request for model: {}", model);
        return ResponseEntity.ok(installModelUseCase.execute(model));
    }

    @Operation(summary = "Send a prompt to any locally available Ollama model via ?model=<name>")
    @PostMapping("/chat")
    public ResponseEntity<String> chat(@RequestParam String model, @RequestBody ChatRequest request) {
        log.info("Received generic chat request for model: {}", model);
        return ResponseEntity.ok(genericChatUseCase.execute(model, request));
    }
}

