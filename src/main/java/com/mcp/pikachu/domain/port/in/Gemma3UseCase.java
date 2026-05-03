package com.mcp.pikachu.domain.port.in;

import com.mcp.pikachu.domain.model.ChatRequest;

public interface Gemma3UseCase {

    String execute(ChatRequest request);
}

