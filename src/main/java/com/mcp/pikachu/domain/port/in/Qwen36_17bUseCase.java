package com.mcp.pikachu.domain.port.in;

import com.mcp.pikachu.domain.model.ChatRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public interface Qwen36_17bUseCase {

    void execute(ChatRequest request, HttpServletResponse response) throws IOException;
}
