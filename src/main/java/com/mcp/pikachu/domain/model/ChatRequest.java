package com.mcp.pikachu.domain.model;

public record ChatRequest(String prompt, String language, String imageBase64) {}
