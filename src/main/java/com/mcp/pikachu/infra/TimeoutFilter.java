package com.mcp.pikachu.infra;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.*;


@Component
public class TimeoutFilter implements Filter {

    @Value("${app.timeout.qwen:120}")
    private long timeoutSeconds;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletResponse httpResponse = (HttpServletResponse) response;

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