package com.mcp.pikachu.domain.port.out;

public interface AiGenerationPort {

    String generateWithLlamaTiny(AiPromptRequest request);

    String generateWithGemini(AiPromptRequest request);

    String generateWithGemma3(AiPromptRequest request);
}

