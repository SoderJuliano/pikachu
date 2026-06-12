package com.mcp.pikachu.domain.port.out;

import com.mcp.pikachu.domain.model.SystemResourceInfo;

public interface SystemResourcePort {

    SystemResourceInfo fetchSystemResources();
}

