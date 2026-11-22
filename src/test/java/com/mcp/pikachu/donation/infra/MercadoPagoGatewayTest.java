package com.mcp.pikachu.donation.infra;

import com.mcp.pikachu.donation.domain.Payment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MercadoPagoGatewayTest {

    final MercadoPagoGateway gw = new MercadoPagoGateway("https://api.mercadopago.com", "TEST", null, null);

    @Test
    void parsesPixPayment() {
        Payment p = gw.parse("""
                {"id":1325467890,"status":"pending","status_detail":"pending_waiting_transfer","payment_method_id":"pix",
                 "transaction_amount":25.5,"currency_id":"BRL","external_reference":"wowlk:42","date_approved":null,
                 "date_of_expiration":"2026-11-22T12:30:00.000-03:00","live_mode":false,
                 "metadata":{"account_id":"42","username":"ARTHAS","email":"a@x.com"},
                 "point_of_interaction":{"transaction_data":{"qr_code":"000201...","qr_code_base64":"iVBORw0","ticket_url":"https://mp/t"}}}""");
        assertEquals("1325467890", p.id());
        assertEquals("pix", p.method());
        assertFalse(p.approved());
        assertEquals(42L, p.accountId());
        assertEquals(new BigDecimal("25.5"), p.amount());
        assertEquals("ARTHAS", p.meta("username"));
        assertEquals("000201...", p.pix().qrCode());
        assertEquals("data:image/png;base64,iVBORw0", p.pix().qrCodeImage());
        assertEquals(Instant.parse("2026-11-22T15:30:00Z"), p.pix().expiresAt());
    }

    @Test
    void parsesApprovedCardPayment() {
        Payment p = gw.parse("""
                {"id":99,"status":"approved","status_detail":"accredited","payment_method_id":"master",
                 "transaction_amount":10,"currency_id":"BRL","external_reference":"wowlk:7",
                 "date_approved":"2026-11-22T10:15:30.123-04:00","live_mode":true,"metadata":{}}""");
        assertTrue(p.approved());
        assertEquals("card", p.method());
        assertEquals(Instant.parse("2026-11-22T14:15:30.123Z"), p.dateApproved());
        assertNull(p.pix());
        assertTrue(p.liveMode());
    }

    @Test
    void foreignReferenceHasNoAccount() {
        assertNull(gw.parse("{\"id\":1,\"status\":\"approved\",\"external_reference\":\"pedido-123\"}").accountId());
        assertNull(gw.parse("{\"id\":1,\"status\":\"approved\",\"external_reference\":\"wowlk:-5\"}").accountId());
    }
}
