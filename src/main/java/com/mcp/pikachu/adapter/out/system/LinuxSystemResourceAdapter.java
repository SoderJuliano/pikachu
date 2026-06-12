package com.mcp.pikachu.adapter.out.system;

import com.mcp.pikachu.domain.model.SystemResourceInfo;
import com.mcp.pikachu.domain.port.out.SystemResourcePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class LinuxSystemResourceAdapter implements SystemResourcePort {

    private static final String PROC_MEMINFO = "/proc/meminfo";
    private static final String PROC_STAT = "/proc/stat";
    private static final long KB_TO_MB = 1024L;

    @Override
    public SystemResourceInfo fetchSystemResources() {
        double cpuPercent = readCpuPercent();
        Map<String, Long> mem = readMemInfo();

        long memTotalKb = mem.getOrDefault("MemTotal", 0L);
        long memAvailableKb = mem.getOrDefault("MemAvailable", 0L);
        long swapTotalKb = mem.getOrDefault("SwapTotal", 0L);
        long swapFreeKb = mem.getOrDefault("SwapFree", 0L);

        long memUsedKb = memTotalKb - memAvailableKb;
        double memPercent = memTotalKb > 0 ? (double) memUsedKb / memTotalKb * 100.0 : 0.0;
        long swapUsedKb = swapTotalKb - swapFreeKb;

        return new SystemResourceInfo(
                Math.round(cpuPercent * 100.0) / 100.0,
                Math.round(memPercent * 100.0) / 100.0,
                memTotalKb / KB_TO_MB,
                memUsedKb / KB_TO_MB,
                swapTotalKb / KB_TO_MB,
                swapUsedKb / KB_TO_MB
        );
    }

    private double readCpuPercent() {
        try {
            long[] first = readCpuStats();
            Thread.sleep(500);
            long[] second = readCpuStats();

            long idleDelta = second[3] - first[3];
            long totalDelta = 0;
            for (int i = 0; i < second.length; i++) {
                totalDelta += second[i] - first[i];
            }

            if (totalDelta == 0) return 0.0;
            return (1.0 - (double) idleDelta / totalDelta) * 100.0;
        } catch (Exception e) {
            log.error("Failed to read CPU stats: {}", e.getMessage());
            return 0.0;
        }
    }

    private long[] readCpuStats() throws IOException {
        String line;
        try (var stream = Files.lines(Paths.get(PROC_STAT))) {
            line = stream.filter(l -> l.startsWith("cpu "))
                    .findFirst()
                    .orElseThrow(() -> new IOException("cpu line not found in /proc/stat"));
        }

        String[] parts = line.trim().split("\\s+");
        long[] values = new long[parts.length - 1];
        for (int i = 0; i < values.length; i++) {
            values[i] = Long.parseLong(parts[i + 1]);
        }
        return values;
    }

    private Map<String, Long> readMemInfo() {
        try {
            List<String> lines = Files.readAllLines(Paths.get(PROC_MEMINFO));
            return lines.stream()
                    .filter(l -> l.contains(":"))
                    .collect(Collectors.toMap(
                            l -> l.split(":")[0].trim(),
                            l -> parseKb(l.split(":")[1].trim())
                    ));
        } catch (IOException e) {
            log.error("Failed to read /proc/meminfo: {}", e.getMessage());
            return Map.of();
        }
    }

    private long parseKb(String value) {
        return Long.parseLong(value.replace("kB", "").trim());
    }
}


