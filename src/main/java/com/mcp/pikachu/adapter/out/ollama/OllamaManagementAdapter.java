package com.mcp.pikachu.adapter.out.ollama;

import com.mcp.pikachu.domain.port.out.OllamaManagementPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@Slf4j
@Component
public class OllamaManagementAdapter implements OllamaManagementPort {

    @Override
    public String pullModel(String model) {
        log.info("Pulling Ollama model: {}", model);
        try {
            ProcessBuilder builder = new ProcessBuilder("ollama", "pull", model);
            builder.redirectErrorStream(true);
            Process process = builder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[ollama pull {}] {}", model, line);
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("ollama pull {} exited with code {}", model, exitCode);
                throw new RuntimeException("Failed to pull model [" + model + "], exit code: " + exitCode);
            }

            log.info("Model [{}] pulled successfully", model);
            return "Model [" + model + "] installed successfully";
        } catch (Exception e) {
            log.error("Error pulling model [{}]: {}", model, e.getMessage());
            throw new RuntimeException("Error pulling model [" + model + "]: " + e.getMessage(), e);
        }
    }
}

