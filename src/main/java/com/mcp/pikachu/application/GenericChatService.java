package com.mcp.pikachu.application;

import com.mcp.pikachu.domain.model.ChatRequest;
import com.mcp.pikachu.domain.port.in.GenericChatUseCase;
import com.mcp.pikachu.domain.port.out.LlmClientPort;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class GenericChatService implements GenericChatUseCase {

    private final LlmClientPort llmClientPort;

    @Override
    public String execute(String model, ChatRequest request) {
        log.info("Processing generic chat request for model: {}", model);
        return llmClientPort.callModel(model, request);
    }

    @Override
    public void executeStream(String model, ChatRequest request, HttpServletResponse response) throws IOException {
        log.info("Processing generic chat stream request for model: {}", model);
        llmClientPort.genericStream(model, request, response);
    }
}

