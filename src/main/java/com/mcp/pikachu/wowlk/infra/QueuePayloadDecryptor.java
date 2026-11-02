package com.mcp.pikachu.wowlk.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcp.pikachu.wowlk.config.WowlkSecrets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Abre o envelope opcional {@code {"enc":"RSA-OAEP-256+A256GCM","key","iv","data"}} do site
 * (VITE_QUEUE_PUBLIC_KEY). Hoje o site publica em texto puro; isto so e usado se
 * WOWLK_QUEUE_PRIVATE_KEY_PATH apontar para o queue-private-key.pem.
 */
@Slf4j
@Component
public class QueuePayloadDecryptor {

    private volatile PrivateKey privateKey;
    private volatile boolean loaded;

    public boolean isEncrypted(JsonNode node) {
        return node != null && node.hasNonNull("enc");
    }

    public String open(JsonNode env) throws Exception {
        if (!"RSA-OAEP-256+A256GCM".equals(env.path("enc").asText()))
            throw new IllegalArgumentException("Unsupported envelope " + env.path("enc").asText());
        PrivateKey pk = key();
        if (pk == null) throw new IllegalStateException("Encrypted payload but WOWLK_QUEUE_PRIVATE_KEY_PATH is not set");

        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.DECRYPT_MODE, pk, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] aesKey = rsa.doFinal(Base64.getDecoder().decode(env.path("key").asText()));

        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new GCMParameterSpec(128, Base64.getDecoder().decode(env.path("iv").asText())));
        return new String(aes.doFinal(Base64.getDecoder().decode(env.path("data").asText())), StandardCharsets.UTF_8);
    }

    private PrivateKey key() throws Exception {
        if (loaded) return privateKey;
        synchronized (this) {
            if (!loaded) {
                String path = WowlkSecrets.queuePrivateKeyPath();
                if (path != null) {
                    String pem = Files.readString(Path.of(path))
                            .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
                    privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
                    log.info("[wowlk] chave privada da fila carregada");
                }
                loaded = true;
            }
        }
        return privateKey;
    }
}
