package com.mcp.pikachu.infra;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import static java.util.Objects.isNull;

@Log4j2
@Component
public class Interceptor implements org.springframework.web.servlet.HandlerInterceptor {

    private static final String API_KEY_HEADER = "apikey";
    private static final String SECRET_KEY = "API_KEY";

    private static final java.util.Set<String> PROTECTED_PATHS = java.util.Set.of(
            "/qwen3.6-17b",
            "/system/metrics"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        log.info("Incoming request: {} {}", request.getMethod(), request.getRequestURI());

        String uri = request.getRequestURI();
        if (!PROTECTED_PATHS.contains(uri)) {
            return true;
        }

        String apikey = request.getHeader(API_KEY_HEADER);
        if (isNull(apikey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Missing API key");
            log.warn("Unauthorized access attempt without API key on {}", uri);
            return false;
        }

        String expectedKey = SecretManager.getSecret(SECRET_KEY);
        if (!apikey.equals(expectedKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Invalid API key");
            log.warn("Unauthorized access attempt with invalid API key on {}", uri);
            return false;
        }

        return true;
    }
}
