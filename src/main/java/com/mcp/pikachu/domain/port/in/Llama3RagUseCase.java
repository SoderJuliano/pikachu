package com.mcp.pikachu.domain.port.in;

import com.mcp.pikachu.domain.model.ChatRequest;

public interface Llama3RagUseCase {

    String execute(ChatRequest request);
}
