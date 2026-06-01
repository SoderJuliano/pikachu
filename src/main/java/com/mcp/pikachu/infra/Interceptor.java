package com.mcp.pikachu.infra;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Log4j2
public class Interceptor implements org.springframework.web.servlet.HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        System.out.println("Incoming request: " + request.getMethod() + " " + request.getRequestURI());

        String apikey = request.getHeader("apikey");

        if(isNull(apikey) && request.getRequestURI().equals("/qwen3.6-17b")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Missing API key");
            log.warn("Unauthorized access attempt without API key");
            return false;
        } else if (nonNull(apikey) && request.getRequestURI().equals("/qwen3.6-17b")) {
            if (!"123".equals(apikey)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("Invalid API key");
                log.warn("Unauthorized access attempt with API key: " + apikey);
                return false;
            }
        }

        return true; // Continue with the next interceptor or the handler
    }
}
