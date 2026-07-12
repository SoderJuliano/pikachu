package com.mcp.pikachu.infra;

import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

@Slf4j
public class SecretManager {

    private static final Properties properties = new Properties();
    private static final String PATH = "/mnt/500gb/app/secrets.txt";

    static {
        try (FileInputStream input = new FileInputStream(PATH)) {
            properties.load(input);
            log.info("Secrets loaded successfully");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load secrets file: " + PATH, e);
        }
    }

    public static String getSecret(String key) {
        return properties.getProperty(key);
    }
}

