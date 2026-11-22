package com.mcp.pikachu.donation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Pagamento como o gateway (Mercado Pago) o reporta. Sempre lido do gateway — nunca do que o
 * navegador ou o corpo do webhook dizem.
 *
 * @param externalReference {@code wowlk:<accountId>}: liga o pagamento a conta do jogo
 * @param metadata          o que gravamos na criacao (account_id, username, email)
 * @param pix               QR Code (so Pix)
 */
public record Payment(
        String id,
        String status,
        String statusDetail,
        String method,
        BigDecimal amount,
        String currency,
        String externalReference,
        Instant dateApproved,
        boolean liveMode,
        Map<String, String> metadata,
        PixData pix
) {
    public static final String APPROVED = "approved";

    public record PixData(String qrCode, String qrCodeImage, String ticketUrl, Instant expiresAt) {
    }

    public boolean approved() {
        return APPROVED.equals(status);
    }

    /** Conta do jogo dona do pagamento, ou null se a referencia nao e nossa. */
    public Long accountId() {
        return DonationRefs.accountIdOf(externalReference);
    }

    public String meta(String key) {
        return metadata == null ? null : metadata.get(key);
    }
}
