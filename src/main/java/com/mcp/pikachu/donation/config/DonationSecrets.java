package com.mcp.pikachu.donation.config;

import com.mcp.pikachu.infra.SecretManager;

/**
 * Segredos do modulo de doacoes, lidos do secrets.txt (fora do git):
 * <ul>
 *   <li>{@code DONATION_MP_ACCESS_TOKEN} — Access Token do Mercado Pago (APP_USR-... da conta vendedora)</li>
 *   <li>{@code DONATION_MP_PUBLIC_KEY} — Public Key (vai para o site tokenizar o cartao; nao e segredo, mas fica junto)</li>
 *   <li>{@code DONATION_MP_WEBHOOK_SECRET} — "Assinatura secreta" do painel de Webhooks (valida o x-signature)</li>
 * </ul>
 */
public final class DonationSecrets {

    private DonationSecrets() {
    }

    public static String accessToken() {
        return get("DONATION_MP_ACCESS_TOKEN");
    }

    public static String publicKey() {
        return get("DONATION_MP_PUBLIC_KEY");
    }

    public static String webhookSecret() {
        return get("DONATION_MP_WEBHOOK_SECRET");
    }

    private static String get(String key) {
        String v = SecretManager.getSecret(key);
        if (v == null || v.isBlank()) v = System.getenv(key); // facilita rodar local sem secrets.txt
        return v == null || v.isBlank() ? null : v.trim();
    }
}
