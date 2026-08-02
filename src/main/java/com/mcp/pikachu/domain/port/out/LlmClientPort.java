package com.mcp.pikachu.domain.port.out;

import com.mcp.pikachu.domain.model.AgentRequest;
import com.mcp.pikachu.domain.model.ChatRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public interface LlmClientPort {

        /**
         * Modo agente: /api/chat do Ollama com messages[] e tools[] NATIVOS.
         * A chamada de ferramenta volta como objeto tipado (tool_calls), nao como
         * texto que o modelo pode confundir com o proprio raciocinio.
         */
        void agentStream(String model, AgentRequest request, HttpServletResponse response) throws IOException;

	String promptLlamaTiny(ChatRequest request);

	String llama3Response(ChatRequest request);

	String getGemmaResponse(ChatRequest request);

	String qwen25Response(ChatRequest request);

	void llamaStreamResponse(ChatRequest request, HttpServletResponse response) throws IOException;

        void qwen36_17bStream(ChatRequest request, HttpServletResponse response) throws IOException;

        void genericStream(String model, ChatRequest request, HttpServletResponse response) throws IOException;

        String callModel(String model, ChatRequest request);

        String getAvailableModels();
}
