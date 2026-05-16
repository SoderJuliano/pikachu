package com.mcp.pikachu.domain.port.in;

import com.mcp.pikachu.domain.model.ChatRequest;

public interface Qwen25UseCase {

    String execute(ChatRequest request);
}
