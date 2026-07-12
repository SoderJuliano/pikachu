package com.mcp.pikachu.domain.port.in;

import com.mcp.pikachu.domain.model.ChatRequest;

public interface GenericChatUseCase {
    String execute(String model, ChatRequest request);
}

