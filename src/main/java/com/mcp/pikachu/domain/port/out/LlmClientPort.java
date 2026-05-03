package com.mcp.pikachu.domain.port.out;

import com.mcp.pikachu.domain.model.ChatRequest;

public interface LlmClientPort {

	String promptLlamaTiny(ChatRequest request);

	String llama3Response(ChatRequest request);

	String getGemmaResponse(ChatRequest request);
}
