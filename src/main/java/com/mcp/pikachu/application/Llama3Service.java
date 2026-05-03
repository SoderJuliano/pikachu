package com.mcp.pikachu.application;

import org.springframework.stereotype.Service;

import com.mcp.pikachu.domain.model.ChatRequest;
import com.mcp.pikachu.domain.port.in.Llama3UseCase;
import com.mcp.pikachu.domain.port.out.LlmClientPort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class Llama3Service implements Llama3UseCase {

    private final LlmClientPort llmClientPort;

    @Override
    public String execute(ChatRequest request) {
        log.info("Processing llama3 request");
        return llmClientPort.llama3Response(request);
    }
}

