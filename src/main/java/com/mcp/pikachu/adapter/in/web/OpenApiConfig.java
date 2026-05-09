package com.mcp.pikachu.adapter.in.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Pikachu AI API",
        version = "1.0",
        description = "AI microservice endpoints for Ollama models"
))
public class OpenApiConfig {
}

