package com.mcp.pikachu.application;

import com.mcp.pikachu.domain.model.ChatRequest;
import com.mcp.pikachu.domain.port.in.Qwen36_17bUseCase;
import com.mcp.pikachu.domain.port.out.LlmClientPort;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
public class Qwen36_17Service implements Qwen36_17bUseCase {

    private final LlmClientPort llmClientPort;

    @Override
    public void execute(ChatRequest request, HttpServletResponse response) throws IOException {
        log.info("Processing qwen3.6-17b stream request");
        llmClientPort.qwen36_17bStream(request, response);
    }
}
