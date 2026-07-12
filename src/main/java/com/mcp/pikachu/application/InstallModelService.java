package com.mcp.pikachu.application;

import com.mcp.pikachu.domain.port.in.InstallModelUseCase;
import com.mcp.pikachu.domain.port.out.OllamaManagementPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstallModelService implements InstallModelUseCase {

    private final OllamaManagementPort ollamaManagementPort;

    @Override
    public String execute(String model) {
        log.info("Installing model: {}", model);
        return ollamaManagementPort.pullModel(model);
    }
}

