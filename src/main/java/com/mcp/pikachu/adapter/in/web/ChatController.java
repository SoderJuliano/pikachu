package com.mcp.pikachu.adapter.in.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/chat")
public class ChatController {

    private final GenerateAiTextUseCase generateAiTextUseCase;

    @PostMapping("/llama-tiny")
    public ResponseEntity<String> generateTextWithLlamaTiny(@RequestBody AiPromptRequest prompt) {
        log.info("Received llama-tiny request");
        return ResponseEntity.ok(generateAiTextUseCase.generateWithLlamaTiny(prompt));
    }

    @PostMapping("/gemini")
    public ResponseEntity<String> generateTextWithGemini(@RequestBody AiPromptRequest prompt) {
        log.info("Received gemini request");
        return ResponseEntity.ok(generateAiTextUseCase.generateWithGemini(prompt));
    }

    @PostMapping("/gemma3")
    public ResponseEntity<String> generateTextWithGemma3(@RequestBody AiPromptRequest prompt) {
        log.info("Received gemma3 request");
        return ResponseEntity.ok(generateAiTextUseCase.generateWithGemma3(prompt));
    }
}
