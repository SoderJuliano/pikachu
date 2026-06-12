package com.mcp.pikachu.adapter.in.web;

import com.mcp.pikachu.domain.model.SystemResourceInfo;
import com.mcp.pikachu.domain.port.in.SystemResourceUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin
@RequestMapping("/system")
@Tag(name = "System", description = "System resource monitoring endpoints")
public class SystemController {

    private final SystemResourceUseCase systemResourceUseCase;

    @Operation(summary = "Get current system resource usage (CPU, memory, swap)")
    @GetMapping("/resources")
    public ResponseEntity<SystemResourceInfo> getResources() {
        log.info("Received system resources request");
        return ResponseEntity.ok(systemResourceUseCase.getSystemResources());
    }
}

