package com.mcp.pikachu.donation.application;

import com.mcp.pikachu.donation.application.DonationService.WebhookOutcome;
import com.mcp.pikachu.donation.config.DonationProperties;
import com.mcp.pikachu.donation.domain.Donation;
import com.mcp.pikachu.donation.infra.InMemoryDonationStore;
import com.mcp.pikachu.donation.infra.MockPaymentGateway;
import com.mcp.pikachu.donation.infra.WebhookSignature;
import com.mcp.pikachu.donation.port.PaymentGateway;
import com.mcp.pikachu.wowlk.domain.AccountRow;
import com.mcp.pikachu.wowlk.domain.ApiException;
import com.mcp.pikachu.wowlk.domain.ErrorCode;
import com.mcp.pikachu.wowlk.infra.WowlkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DonationServiceTest {

    static final AccountRow ARTHAS = new AccountRow(7, "ARTHAS", "arthas@test.com", new byte[32], new byte[32], false);
    static final AccountRow JAINA = new AccountRow(8, "JAINA", "jaina@test.com", new byte[32], new byte[32], false);
    static final String SECRET = "whsec";

    MockPaymentGateway gateway;
    InMemoryDonationStore store;
    DonationService service;
    Clock clock = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);

    static DonationProperties props(String testPayer) {
        return props(testPayer, BigDecimal.ZERO);
    }

    static DonationProperties props(String testPayer, BigDecimal fixed) {
        return new DonationProperties(true, "mock", "memory", true, "https://api.mercadopago.com", "", testPayer,
                "BRL", new BigDecimal("5"), new BigDecimal("1000"), fixed, new BigDecimal("5.04"),
                30, "desc", "CLASSICPLUS");
    }

    @Test
    void fixedAmountIgnoresWhatTheBrowserSends() {
        service = new DonationService(gateway, store, mock(WowlkRepository.class), props("", new BigDecimal("29.90")), SECRET, "pk", clock);
        assertEquals(new BigDecimal("29.90"), service.createPix(ARTHAS, new BigDecimal("1"), "a").amount());
        assertEquals(new BigDecimal("29.90"), service.createPix(ARTHAS, null, "b").amount());
        var cfg = service.publicConfig();
        assertEquals(new BigDecimal("29.90"), cfg.fixedAmount());
        assertEquals(new BigDecimal("5.04"), cfg.usdEquivalent());
    }

    @BeforeEach
    void setUp() {
        gateway = new MockPaymentGateway();
        store = new InMemoryDonationStore();
        service = new DonationService(gateway, store, mock(WowlkRepository.class), props(""), SECRET, "APP_USR-pk", clock);
    }

    String signed(String paymentId) {
        return WebhookSignature.header(SECRET, "req-1", paymentId, clock.instant().getEpochSecond());
    }

    @Test
    void pixIsOnlyCompletedAfterTheWebhookRecordsIt() {
        var pix = service.createPix(ARTHAS, new BigDecimal("25"), "k1");
        assertEquals("pending", pix.status());
        assertFalse(pix.recorded());
        assertNotNull(pix.pix().qrCode());
        assertFalse(service.supporter(ARTHAS).supporter());

        // webhook chega antes do pagamento: nada e gravado
        assertEquals(WebhookOutcome.NOT_APPROVED, service.handleWebhook(pix.id(), "payment", signed(pix.id()), "req-1"));
        assertFalse(service.status(ARTHAS, pix.id()).recorded());

        gateway.approve(pix.id());
        var done = service.status(ARTHAS, pix.id());
        assertTrue(done.recorded(), "ao aprovar no gateway, polling de status auto-registra a doacao");
        assertEquals("approved", done.status());

        assertEquals(WebhookOutcome.ALREADY_RECORDED, service.handleWebhook(pix.id(), "payment", signed(pix.id()), "req-1"));

        Donation d = store.findByPaymentId(pix.id()).orElseThrow();
        assertEquals(7, d.accountId());
        assertEquals("arthas@test.com", d.email());
        assertEquals("ARTHAS", d.username());
        assertEquals(new BigDecimal("25.00"), d.amount());
        assertNotNull(d.paidAt());
        assertEquals(clock.instant(), d.recordedAt());

        var sup = service.supporter(ARTHAS);
        assertTrue(sup.supporter());
        assertEquals(1, sup.donations());
    }

    @Test
    void repeatedWebhookDoesNotDuplicate() {
        var pix = service.createPix(ARTHAS, new BigDecimal("10"), "k1");
        gateway.approve(pix.id());
        assertEquals(WebhookOutcome.RECORDED, service.handleWebhook(pix.id(), "payment", signed(pix.id()), "req-1"));
        assertEquals(WebhookOutcome.ALREADY_RECORDED, service.handleWebhook(pix.id(), "payment", signed(pix.id()), "req-1"));
        assertEquals(1, store.findByAccount(7).size());
    }

    @Test
    void forgedWebhookIsRejected() {
        var pix = service.createPix(ARTHAS, new BigDecimal("10"), "k1");
        gateway.approve(pix.id());
        assertEquals(WebhookOutcome.INVALID_SIGNATURE, service.handleWebhook(pix.id(), "payment", "ts=1,v1=deadbeef", "req-1"));
        assertEquals(WebhookOutcome.INVALID_SIGNATURE, service.handleWebhook(pix.id(), "payment", null, null));
        assertTrue(store.findByPaymentId(pix.id()).isEmpty());
    }

    @Test
    void unknownPaymentAndOtherTopicsAreIgnored() {
        assertEquals(WebhookOutcome.IGNORED, service.handleWebhook("123", "payment", signed("123"), "req-1"));
        assertEquals(WebhookOutcome.IGNORED, service.handleWebhook("123", "merchant_order", signed("123"), "req-1"));
    }

    @Test
    void cardApprovedStillWaitsForWebhook() {
        var card = service.createCard(ARTHAS, new BigDecimal("50"),
                new PaymentGateway.Card("mock-APRO", "master", null, 1, null, null), "k2");
        assertEquals("approved", card.status());
        assertFalse(card.recorded());
        assertEquals(WebhookOutcome.RECORDED, service.simulateWebhook(card.id()));
        assertTrue(service.status(ARTHAS, card.id()).recorded());
    }

    @Test
    void rejectedCardIsNeverRecorded() {
        var card = service.createCard(ARTHAS, new BigDecimal("50"),
                new PaymentGateway.Card("mock-OTHE", "master", null, 1, null, null), "k3");
        assertEquals("rejected", card.status());
        assertEquals(WebhookOutcome.NOT_APPROVED, service.simulateWebhook(card.id()));
        assertFalse(service.supporter(ARTHAS).supporter());
    }

    @Test
    void cannotSeeSomeoneElsesPayment() {
        var pix = service.createPix(ARTHAS, new BigDecimal("10"), "k1");
        ApiException e = assertThrows(ApiException.class, () -> service.status(JAINA, pix.id()));
        assertEquals(ErrorCode.NOT_FOUND, e.code());
        gateway.approve(pix.id());
        service.simulateWebhook(pix.id());
        assertThrows(ApiException.class, () -> service.status(JAINA, pix.id()));
        assertFalse(service.supporter(JAINA).supporter());
    }

    @Test
    void amountLimits() {
        assertThrows(ApiException.class, () -> service.createPix(ARTHAS, new BigDecimal("4.99"), null));
        assertThrows(ApiException.class, () -> service.createPix(ARTHAS, new BigDecimal("1000.01"), null));
        assertThrows(ApiException.class, () -> service.createPix(ARTHAS, new BigDecimal("10.001"), null));
        assertThrows(ApiException.class, () -> service.createPix(ARTHAS, null, null));
        assertEquals(new BigDecimal("5.00"), service.createPix(ARTHAS, new BigDecimal("5"), null).amount());
    }

    @Test
    void sameIdempotencyKeyDoesNotChargeTwice() {
        var a = service.createPix(ARTHAS, new BigDecimal("10"), "same");
        var b = service.createPix(ARTHAS, new BigDecimal("10"), "same");
        assertEquals(a.id(), b.id());
    }

    @Test
    void testPayerEmailIsSentToGatewayButDonationKeepsGameEmail() {
        service = new DonationService(gateway, store, mock(WowlkRepository.class), props("buyer@testuser.com"), SECRET, "pk", clock);
        var pix = service.createPix(ARTHAS, new BigDecimal("10"), "k1");
        gateway.approve(pix.id());
        service.simulateWebhook(pix.id());
        assertEquals("arthas@test.com", store.findByPaymentId(pix.id()).orElseThrow().email());
    }
}
