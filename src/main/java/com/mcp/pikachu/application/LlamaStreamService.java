package com.mcp.pikachu.application;

import com.mcp.pikachu.domain.model.ChatRequest;
import com.mcp.pikachu.domain.port.in.LlamaStreamUseCase;
import com.mcp.pikachu.domain.port.out.LlmClientPort;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@Slf4j
@RequiredArgsConstructor
public class LlamaStreamService implements LlamaStreamUseCase {

    private final LlmClientPort llmClientPort;

    @Override
    public void llamaStream(ChatRequest request, HttpServletResponse response) throws IOException {
        log.info("Processing llama stream request");
        llmClientPort.llamaStreamResponse(request, response);
    }
}
