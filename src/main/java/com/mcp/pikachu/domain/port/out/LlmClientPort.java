package com.mcp.pikachu.domain.port.out;

import com.mcp.pikachu.domain.model.ChatRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public interface LlmClientPort {

	String promptLlamaTiny(ChatRequest request);

	String llama3Response(ChatRequest request);

	String getGemmaResponse(ChatRequest request);

	String qwen25Response(ChatRequest request);

	void llamaStreamResponse(ChatRequest request, HttpServletResponse response) throws IOException;

	String qwen36_17bResponse(ChatRequest request);
}
