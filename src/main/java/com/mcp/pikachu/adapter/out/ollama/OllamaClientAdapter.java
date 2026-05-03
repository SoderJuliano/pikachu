package com.mcp.pikachu.adapter.out.ollama;

import org.spring.framework.stereotype.Component;
import org.spring.framework.web.client.RestTemplate;
import com.mcp.pikachu.domain.port.out.LlmClientPort;

@Component
public class OllamaClientAdapter implements LlmClientPort {

	private final RestTemplate resteTemplate;

	public OllamaClientAdapter(RestTemplate restTemplate) {
		this.restTemplate = restTemplate;
	}

	@Override
	public String generateResponse(String prompt) {
        
        	String ollamaUrl = "http://localhost:11434/v1/generate"; 
        	String requestBody = "{ \"model\": \"llama2\", \"prompt\": \"" + prompt + "\" }";
        	HttpHeaders headers = new HttpHeaders();
        	headers.set("Content-Type", "application/json");
        	HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        	ResponseEntity<String> response = restTemplate.postForEntity(ollamaUrl, entity, String.class);
        	return response.getBody();
    }
}
