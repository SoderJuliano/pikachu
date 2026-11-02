package com.mcp.pikachu.wowlk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/** Configuracao nao-secreta do modulo wowlk (application.yaml, prefixo {@code wowlk}). */
@ConfigurationProperties("wowlk")
public record WowlkProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("https://abra-api.top") String abraBaseUrl,
        @DefaultValue("120000") long pollIntervalMs,
        @DefaultValue("20000") long pollInitialDelayMs,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("100") int maxRegistrationsPerRun,
        @DefaultValue("900") long accessTokenTtlSeconds,
        @DefaultValue("2592000") long refreshTokenTtlSeconds,
        @DefaultValue("0.1.0") String minLauncherVersion,
        @DefaultValue("false") boolean maintenance,
        @DefaultValue("") String realmlist,
        @DefaultValue({}) List<String> corsOrigins
) {
}
