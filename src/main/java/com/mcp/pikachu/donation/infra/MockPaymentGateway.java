package com.mcp.pikachu.donation.infra;

import com.mcp.pikachu.donation.domain.Payment;
import com.mcp.pikachu.donation.port.PaymentGateway;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gateway falso para testar o fluxo inteiro em localhost, sem rede e sem Mercado Pago:
 * Pix nasce pendente (QR Code de mentira); cartao com titular "APRO" aprova na hora, "OTHE" recusa
 * (mesma convencao dos cartoes de teste do Mercado Pago). {@link #approve} faz o papel do comprador
 * pagando o Pix — a rota de dev chama isso e depois dispara o webhook.
 */
public class MockPaymentGateway implements PaymentGateway {

    private final ConcurrentHashMap<String, Payment> payments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> byIdempotencyKey = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(900_000_000L);

    @Override
    public Payment createPix(Charge charge, int expirationMinutes) {
        return byIdempotencyKey.containsKey(charge.idempotencyKey())
                ? payments.get(byIdempotencyKey.get(charge.idempotencyKey()))
                : store(charge, "pix", "pending", "pending_waiting_transfer", expirationMinutes);
    }

    @Override
    public Payment createCard(Charge charge, Card card) {
        if (byIdempotencyKey.containsKey(charge.idempotencyKey())) return payments.get(byIdempotencyKey.get(charge.idempotencyKey()));
        boolean rejected = card.token() != null && card.token().toUpperCase().contains("OTHE");
        return store(charge, "card", rejected ? "rejected" : "approved", rejected ? "cc_rejected_other_reason" : "accredited", 0);
    }

    @Override
    public Optional<Payment> fetch(String paymentId) {
        return Optional.ofNullable(paymentId == null ? null : payments.get(paymentId));
    }

    /** Simula o comprador pagando. Retorna false se o pagamento nao existe. */
    public boolean approve(String paymentId) {
        return payments.computeIfPresent(paymentId, (id, p) -> p.approved() ? p : new Payment(p.id(), Payment.APPROVED, "accredited",
                p.method(), p.amount(), p.currency(), p.externalReference(), Instant.now(), false, p.metadata(), p.pix())) != null;
    }

    private Payment store(Charge c, String method, String status, String detail, int expirationMinutes) {
        String id = String.valueOf(seq.incrementAndGet());
        Payment.PixData pix = null;
        if ("pix".equals(method)) {
            String code = "00020126580014br.gov.bcb.pix0136mock-" + id + "5204000053039865406" + c.amount() + "5802BR5913MOCK DONATION6009SAO PAULO6304ABCD";
            pix = new Payment.PixData(code, mockQrImage(id), null, Instant.now().plusSeconds(expirationMinutes * 60L));
        }
        Payment p = new Payment(id, status, detail, method, c.amount(), "BRL", c.externalReference(),
                "approved".equals(status) ? Instant.now() : null, false, Map.copyOf(c.metadata()), pix);
        payments.put(id, p);
        byIdempotencyKey.put(c.idempotencyKey(), id);
        return p;
    }

    /** SVG de placeholder: deixa claro na tela que e mock, sem precisar de biblioteca de QR. */
    private static String mockQrImage(String id) {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="240" height="240" viewBox="0 0 240 240">
                <rect width="240" height="240" fill="#fff"/><rect x="16" y="16" width="56" height="56" fill="#111"/>
                <rect x="168" y="16" width="56" height="56" fill="#111"/><rect x="16" y="168" width="56" height="56" fill="#111"/>
                <text x="120" y="118" font-family="monospace" font-size="20" text-anchor="middle" fill="#111">MOCK PIX</text>
                <text x="120" y="142" font-family="monospace" font-size="12" text-anchor="middle" fill="#555">%s</text></svg>""".formatted(id);
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    }
}
