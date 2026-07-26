package com.mcp.pikachu.domain.port.in;

import com.mcp.pikachu.domain.model.ChatRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public interface GenericChatUseCase {
    String execute(String model, ChatRequest request);
    void executeStream(String model, ChatRequest request, HttpServletResponse response) throws IOException;
}

