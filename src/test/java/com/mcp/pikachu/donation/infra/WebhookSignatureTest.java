package com.mcp.pikachu.donation.infra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureTest {

    static final String SECRET = "segredo-do-painel";

    @Test
    void manifestFollowsMercadoPagoFormat() {
        assertEquals("id:123456;request-id:req-1;ts:1704908010;", WebhookSignature.manifest("123456", "req-1", "1704908010"));
        assertEquals("id:abc;ts:10;", WebhookSignature.manifest("ABC", null, "10"));
    }

    @Test
    void acceptsOwnSignatureAndRejectsTampering() {
        String header = WebhookSignature.header(SECRET, "req-1", "123456", 1704908010L);
        assertTrue(WebhookSignature.isValid(SECRET, header, "req-1", "123456"));
        assertFalse(WebhookSignature.isValid(SECRET, header, "req-1", "999999"), "outro pagamento");
        assertFalse(WebhookSignature.isValid(SECRET, header, "req-2", "123456"), "outro request-id");
        assertFalse(WebhookSignature.isValid("outro-segredo", header, "req-1", "123456"));
        assertFalse(WebhookSignature.isValid(SECRET, null, "req-1", "123456"));
        assertFalse(WebhookSignature.isValid(SECRET, "ts=1", "req-1", "123456"));
    }

    @Test
    void matchesKnownHmac() {
        // HMAC-SHA256("segredo-do-painel", "id:1;request-id:r;ts:2;") calculado fora (Python hmac).
        String expected = "ts=2,v1=a2c5c5bceb6e010df11c7bfcf36f872519e59c8d655970f0fb7ecb546693ca50";
        assertEquals(expected, WebhookSignature.header(SECRET, "r", "1", 2));
        assertTrue(WebhookSignature.isValid(SECRET, expected.toUpperCase().replace("TS=", "ts=").replace("V1=", "v1="), "r", "1"));
    }
}
