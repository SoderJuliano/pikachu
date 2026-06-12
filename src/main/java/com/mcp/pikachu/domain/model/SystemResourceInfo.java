package com.mcp.pikachu.domain.model;

public record SystemResourceInfo(
        double cpuPercent,
        double memoryPercent,
        long memoryTotalMb,
        long memoryUsedMb,
        long swapTotalMb,
        long swapUsedMb
) {}

