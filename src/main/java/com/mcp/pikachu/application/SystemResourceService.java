package com.mcp.pikachu.application;

import com.mcp.pikachu.domain.model.SystemResourceInfo;
import com.mcp.pikachu.domain.port.in.SystemResourceUseCase;
import com.mcp.pikachu.domain.port.out.SystemResourcePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemResourceService implements SystemResourceUseCase {

    private final SystemResourcePort systemResourcePort;

    @Override
    public SystemResourceInfo getSystemResources() {
        log.info("Fetching system resources");
        return systemResourcePort.fetchSystemResources();
    }
}

