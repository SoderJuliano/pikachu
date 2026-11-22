package com.mcp.pikachu.donation.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Doacao CONFIRMADA — uma linha em {@code wowlk_site.donation}. So nasce de um pagamento
 * aprovado lido do gateway a partir do webhook. O {@code paymentId} e a chave primaria: reentregas do
 * webhook nao duplicam.
 *
 * @param accountId  id da conta do jogo (acore_auth.account.id)
 * @param email      e-mail da conta do jogo
 * @param paidAt     data/hora da aprovacao no Mercado Pago (date_approved)
 * @param recordedAt quando o webhook registrou
 */
public record Donation(
        String paymentId,
        long accountId,
        String username,
        String email,
        BigDecimal amount,
        String currency,
        String method,
        Instant paidAt,
        Instant recordedAt,
        boolean liveMode
) {
}
