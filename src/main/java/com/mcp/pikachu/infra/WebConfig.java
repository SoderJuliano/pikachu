package com.mcp.pikachu.infra;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final Interceptor interceptor;

    public WebConfig(Interceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }
}
