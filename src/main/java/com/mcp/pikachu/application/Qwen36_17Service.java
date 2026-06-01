package com.mcp.pikachu.application;

import com.mcp.pikachu.domain.model.ChatRequest;
import com.mcp.pikachu.domain.port.in.Qwen36_17bUseCase;
import com.mcp.pikachu.domain.port.out.LlmClientPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class Qwen36_17Service implements Qwen36_17bUseCase {

    private final LlmClientPort llmClientPort;

    @Override
    public String execute(ChatRequest request) {
        log.info("Processing qwen3.6-17b request");
        return llmClientPort.qwen36_17bResponse(request);
    }
}
