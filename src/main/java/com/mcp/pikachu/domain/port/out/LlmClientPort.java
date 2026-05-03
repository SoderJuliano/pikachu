package com.mcp.pikachu.domain.port.out.LlmClientPort;

public interface LlmClientPort {

	String generateResponse(String prompt);
}
