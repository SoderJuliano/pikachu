package com.mcp.pikachu.domain.port.in;

import com.mcp.pikachu.domain.model.ChatRequest;

public interface Qwen36_17bUseCase {

    String execute(ChatRequest prompt);
}
