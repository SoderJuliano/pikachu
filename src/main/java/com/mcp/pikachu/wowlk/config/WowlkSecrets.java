package com.mcp.pikachu.wowlk.config;

import com.mcp.pikachu.infra.SecretManager;

/**
 * Segredos do modulo, lidos do secrets.txt via {@link SecretManager} (fora do git).
 * As chaves da fila Abra sao tratadas como segredo: quem tem a chave le os cadastros.
 */
public final class WowlkSecrets {

    private WowlkSecrets() {
    }

    public static String registrationKey() {
        return get("WOWLK_QUEUE_REGISTRATION_KEY");
    }

    public static String eventsKey() {
        return get("WOWLK_QUEUE_EVENTS_KEY");
    }

    public static String dbUrl() {
        return get("WOWLK_DB_URL");
    }

    public static String dbUser() {
        return get("WOWLK_DB_USER");
    }

    public static String dbPassword() {
        return get("WOWLK_DB_PASSWORD");
    }

    /** Opcional: PEM PKCS#8 gerado por {@code npm run queue:keygen} no site. */
    public static String queuePrivateKeyPath() {
        return get("WOWLK_QUEUE_PRIVATE_KEY_PATH");
    }

    private static String get(String key) {
        String v = SecretManager.getSecret(key);
        return v == null || v.isBlank() ? null : v.trim();
    }
}
