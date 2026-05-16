package com.mcp.pikachu.application;

import org.springframework.stereotype.Service;

import com.mcp.pikachu.domain.model.ChatRequest;
import com.mcp.pikachu.domain.port.in.Qwen25UseCase;
import com.mcp.pikachu.domain.port.out.LlmClientPort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class Qwen25Service implements Qwen25UseCase {

    private final LlmClientPort llmClientPort;

    @Override
    public String execute(ChatRequest request) {
        log.info("Processing qwen2.5 request");
        return llmClientPort.qwen25Response(request);
    }
}

