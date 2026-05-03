package com.mcp.pikachu.domain.port.in;

import com.mcp.pikachu.domain.model.ChatRequest;
import com.mcp.pikachu.domain.model.ChatResponse;

public interface ProcessChatUseCase {

	ChatResponse process(ChatRequest request);
}
