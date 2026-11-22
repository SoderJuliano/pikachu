package com.mcp.pikachu.donation.infra;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Validacao do cabecalho {@code x-signature} dos webhooks do Mercado Pago.
 * <p>
 * Formato: {@code x-signature: ts=1704908010,v1=<hex>}. O {@code v1} e o HMAC-SHA256, com a
 * "assinatura secreta" do painel, do manifesto {@code id:<data.id>;request-id:<x-request-id>;ts:<ts>;}
 * ({@code data.id} vem da query string e vai em minusculas se for alfanumerico; partes ausentes saem do manifesto).
 */
public final class WebhookSignature {

    private WebhookSignature() {
    }

    public static boolean isValid(String secret, String xSignature, String xRequestId, String dataId) {
        if (secret == null || xSignature == null) return false;
        String ts = null, v1 = null;
        for (String part : xSignature.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            String k = kv[0].trim(), v = kv[1].trim();
            if (k.equals("ts")) ts = v;
            else if (k.equals("v1")) v1 = v;
        }
        if (ts == null || v1 == null) return false;
        String expected = sign(secret, manifest(dataId, xRequestId, ts));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), v1.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    /** Monta o cabecalho como o Mercado Pago faria — usado pela rota de simulacao em dev e nos testes. */
    public static String header(String secret, String xRequestId, String dataId, long ts) {
        return "ts=" + ts + ",v1=" + sign(secret, manifest(dataId, xRequestId, String.valueOf(ts)));
    }

    static String manifest(String dataId, String xRequestId, String ts) {
        StringBuilder m = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            String id = dataId.matches("[A-Za-z0-9]+") ? dataId.toLowerCase(Locale.ROOT) : dataId;
            m.append("id:").append(id).append(';');
        }
        if (xRequestId != null && !xRequestId.isBlank()) m.append("request-id:").append(xRequestId).append(';');
        m.append("ts:").append(ts).append(';');
        return m.toString();
    }

    private static String sign(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
