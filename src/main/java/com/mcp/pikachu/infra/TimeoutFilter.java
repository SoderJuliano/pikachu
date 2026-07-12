package com.mcp.pikachu.infra;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;


@Component
public class TimeoutFilter implements Filter {

    @Value("${app.timeout.qwen:120}")
    private long timeoutSeconds;

    // Endpoints de streaming (SSE) gerenciam o próprio timeout e não podem ser
    // interrompidos por este filtro, pois a resposta já foi enviada parcialmente.
    private static final Set<String> STREAMING_PATHS = Set.of("/qwen3.6-17b", "/llama3-stream");

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (request instanceof HttpServletRequest httpRequest
                && STREAMING_PATHS.contains(httpRequest.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        Future<?> future = executor.submit(() -> {
            try {
                chain.doFilter(request, response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            httpResponse.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
            httpResponse.getWriter().write("Request timeout");
        } catch (Exception e) {
            httpResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}