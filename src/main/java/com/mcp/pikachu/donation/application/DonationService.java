package com.mcp.pikachu.donation.application;

import com.mcp.pikachu.donation.config.DonationProperties;
import com.mcp.pikachu.donation.domain.Donation;
import com.mcp.pikachu.donation.domain.DonationRefs;
import com.mcp.pikachu.donation.domain.Payment;
import com.mcp.pikachu.donation.infra.WebhookSignature;
import com.mcp.pikachu.donation.port.DonationStore;
import com.mcp.pikachu.donation.port.PaymentGateway;
import com.mcp.pikachu.wowlk.domain.AccountRow;
import com.mcp.pikachu.wowlk.domain.ApiException;
import com.mcp.pikachu.wowlk.domain.ErrorCode;
import com.mcp.pikachu.wowlk.infra.RateLimiter;
import com.mcp.pikachu.wowlk.infra.WowlkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Doacoes via Mercado Pago.
 * <p>
 * Regra central: <b>so o webhook registra a doacao</b>. Ele nunca confia no corpo da notificacao —
 * relê o pagamento direto na API do Mercado Pago pelo id e so grava em {@code wowlk_site.donation} se o status
 * for {@code approved}. O site so mostra "pagamento concluido" quando o registro existe.
 */
@Slf4j
public class DonationService {

    public record PixView(String qrCode, String qrCodeImage, String ticketUrl, Instant expiresAt) {
    }

    /** O que o site recebe. {@code recorded=true} = o webhook confirmou e gravou a doacao. */
    public record PaymentView(String id, String status, String statusDetail, String method, BigDecimal amount,
                              String currency, boolean recorded, Instant paidAt, Instant recordedAt, PixView pix) {
    }

    public record SupporterView(boolean supporter, Instant since, int donations) {
    }

    /** {@code fixedAmount} != null = valor unico; {@code usdEquivalent} so para exibir fora do Brasil. */
    public record PublicConfig(String gateway, String publicKey, String currency, BigDecimal minAmount,
                               BigDecimal maxAmount, BigDecimal fixedAmount, BigDecimal usdEquivalent, boolean devTools) {
    }

    public enum WebhookOutcome {RECORDED, ALREADY_RECORDED, NOT_APPROVED, IGNORED, INVALID_SIGNATURE}

    private final PaymentGateway gateway;
    private final DonationStore store;
    private final WowlkRepository accounts;
    private final DonationProperties props;
    private final String webhookSecret;
    private final String publicKey;
    private final Clock clock;
    private final RateLimiter createPerAccount;

    public DonationService(PaymentGateway gateway, DonationStore store, WowlkRepository accounts, DonationProperties props,
                           String webhookSecret, String publicKey, Clock clock) {
        this.gateway = gateway;
        this.store = store;
        this.accounts = accounts;
        this.props = props;
        this.webhookSecret = webhookSecret;
        this.publicKey = publicKey;
        this.clock = clock;
        this.createPerAccount = new RateLimiter(10, 60 * 60_000, clock);
    }

    public PublicConfig publicConfig() {
        BigDecimal fixed = props.hasFixedAmount() ? props.fixedAmount().setScale(2, RoundingMode.HALF_UP) : null;
        BigDecimal usd = fixed != null && props.usdEquivalent() != null && props.usdEquivalent().signum() > 0
                ? props.usdEquivalent().setScale(2, RoundingMode.HALF_UP) : null;
        return new PublicConfig(props.mockGateway() ? "mock" : "mercadopago", publicKey, props.currency(),
                fixed != null ? fixed : props.minAmount(), fixed != null ? fixed : props.maxAmount(), fixed, usd, props.devTools());
    }

    // ------------------------------------------------------------------ criacao

    public PaymentView createPix(AccountRow account, BigDecimal amount, String idempotencyKey) {
        PaymentGateway.Charge charge = charge(account, amount, idempotencyKey);
        Payment p = gateway.createPix(charge, props.pixExpirationMinutes());
        log.info("[donation] Pix criado: pagamento {} conta {} (id {}) valor {} status {}", p.id(), account.username(), account.id(), p.amount(), p.status());
        return view(p, null);
    }

    public PaymentView createCard(AccountRow account, BigDecimal amount, PaymentGateway.Card card, String idempotencyKey) {
        if (card == null || blank(card.token()) || blank(card.paymentMethodId()))
            throw new ApiException(ErrorCode.VALIDATION, "Card data is incomplete. Check the card and try again.");
        if (card.paymentMethodId().length() > 32 || card.token().length() > 128)
            throw new ApiException(ErrorCode.VALIDATION, "Card data is invalid.");
        PaymentGateway.Charge charge = charge(account, amount, idempotencyKey);
        Payment p = gateway.createCard(charge, new PaymentGateway.Card(card.token(), card.paymentMethodId(), card.issuerId(), 1,
                card.identificationType(), digits(card.identificationNumber())));
        log.info("[donation] cartao: pagamento {} conta {} (id {}) valor {} status {} ({})", p.id(), account.username(), account.id(),
                p.amount(), p.status(), p.statusDetail());
        // Mesmo aprovado na hora, a doacao so e registrada quando o webhook chegar e reconfirmar.
        return view(p, null);
    }

    private PaymentGateway.Charge charge(AccountRow account, BigDecimal rawAmount, String idempotencyKey) {
        BigDecimal amount = validAmount(rawAmount);
        long wait = createPerAccount.hit("acc:" + account.id());
        if (wait > 0) throw new ApiException(ErrorCode.RATE_LIMITED, "Too many payment attempts. Please wait a few minutes.", wait);
        String payerEmail = !blank(props.testPayerEmail()) ? props.testPayerEmail() : account.email();
        if (blank(payerEmail)) throw new ApiException(ErrorCode.VALIDATION, "Your account has no e-mail address.");
        String key = blank(idempotencyKey) || idempotencyKey.length() > 64 || !idempotencyKey.matches("[A-Za-z0-9-]+")
                ? UUID.randomUUID().toString() : idempotencyKey;
        Map<String, String> metadata = Map.of(
                "account_id", String.valueOf(account.id()),
                "username", account.username(),
                "email", account.email() == null ? "" : account.email());
        return new PaymentGateway.Charge(amount, props.description(), payerEmail, DonationRefs.forAccount(account.id()),
                metadata, "wowlk-" + account.id() + "-" + key);
    }

    BigDecimal validAmount(BigDecimal raw) {
        // Valor unico configurado no servidor: o navegador nao escolhe quanto paga.
        if (props.hasFixedAmount()) return props.fixedAmount().setScale(2, RoundingMode.HALF_UP);
        if (raw == null) throw new ApiException(ErrorCode.VALIDATION, "Choose an amount.");
        BigDecimal a;
        try {
            a = raw.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new ApiException(ErrorCode.VALIDATION, "Use at most two decimal places.");
        }
        if (a.compareTo(props.minAmount()) < 0 || a.compareTo(props.maxAmount()) > 0)
            throw new ApiException(ErrorCode.VALIDATION, "The amount must be between " + props.minAmount() + " and " + props.maxAmount() + " " + props.currency() + ".");
        return a;
    }

    // ------------------------------------------------------------------ consulta (o site faz polling)

    public PaymentView status(AccountRow account, String paymentId) {
        if (blank(paymentId) || !paymentId.matches("[A-Za-z0-9_-]{1,64}")) throw new ApiException(ErrorCode.NOT_FOUND, "Payment not found.");
        Optional<Donation> recorded = store.findByPaymentId(paymentId);
        if (recorded.isPresent()) {
            Donation d = recorded.get();
            if (d.accountId() != account.id()) throw new ApiException(ErrorCode.NOT_FOUND, "Payment not found.");
            return new PaymentView(d.paymentId(), Payment.APPROVED, "accredited", d.method(), d.amount(), d.currency(),
                    true, d.paidAt(), d.recordedAt(), null);
        }
        Payment p = gateway.fetch(paymentId)
                .filter(x -> Long.valueOf(account.id()).equals(x.accountId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Payment not found."));
        return view(p, null);
    }

    public SupporterView supporter(AccountRow account) {
        List<Donation> list = store.findByAccount(account.id());
        return new SupporterView(!list.isEmpty(), list.isEmpty() ? null : list.getFirst().paidAt(), list.size());
    }

    // ------------------------------------------------------------------ webhook

    /**
     * Notificacao do Mercado Pago. Aceita o formato novo (Webhooks: {@code ?data.id=..&type=payment})
     * e o antigo (IPN: {@code ?id=..&topic=payment}). Com segredo configurado, o {@code x-signature}
     * e obrigatorio. De qualquer forma o pagamento e relido na API — o corpo nunca e confiavel.
     */
    public WebhookOutcome handleWebhook(String dataId, String type, String xSignature, String xRequestId) {
        if (webhookSecret != null) {
            if (!WebhookSignature.isValid(webhookSecret, xSignature, xRequestId, dataId)) {
                log.warn("[donation] webhook com assinatura INVALIDA (data.id={}, request-id={}) — ignorado", safe(dataId), safe(xRequestId));
                return WebhookOutcome.INVALID_SIGNATURE;
            }
        } else {
            log.warn("[donation] DONATION_MP_WEBHOOK_SECRET ausente: webhook aceito sem validar assinatura (o pagamento ainda e relido no Mercado Pago)");
        }
        if (type != null && !type.equals("payment") && !type.equals("order")) {
            log.info("[donation] webhook tipo '{}' ignorado", safe(type));
            return WebhookOutcome.IGNORED;
        }
        if (blank(dataId) || !dataId.matches("[A-Za-z0-9_-]{1,64}")) return WebhookOutcome.IGNORED;
        return confirm(dataId);
    }

    /** Relê o pagamento no gateway e registra se aprovado. Idempotente. */
    WebhookOutcome confirm(String paymentId) {
        Optional<Payment> found = gateway.fetch(paymentId);
        if (found.isEmpty()) {
            log.warn("[donation] webhook: pagamento {} nao existe no Mercado Pago", paymentId);
            return WebhookOutcome.IGNORED;
        }
        Payment p = found.get();
        Long accountId = p.accountId();
        if (accountId == null) {
            log.info("[donation] webhook: pagamento {} nao e do WoW (external_reference={})", paymentId, safe(p.externalReference()));
            return WebhookOutcome.IGNORED;
        }
        if (!p.approved()) {
            log.info("[donation] webhook: pagamento {} da conta {} esta '{}' ({}) — nada a registrar", paymentId, accountId, p.status(), p.statusDetail());
            return WebhookOutcome.NOT_APPROVED;
        }
        String username = p.meta("username"), email = p.meta("email");
        if (blank(username) || blank(email)) {
            Optional<AccountRow> acc = safeFindAccount(accountId);
            if (blank(username)) username = acc.map(AccountRow::username).orElse(null);
            if (blank(email)) email = acc.map(AccountRow::email).orElse(null);
        }
        Instant now = clock.instant();
        Donation d = new Donation(p.id(), accountId, username, email, p.amount(), p.currency(), p.method(),
                p.dateApproved() != null ? p.dateApproved() : now, now, p.liveMode());
        boolean inserted = store.saveIfAbsent(d);
        if (inserted)
            log.info("[donation] DOACAO REGISTRADA: pagamento {} conta {} (id {}) {} {} via {} pago em {}", p.id(), username, accountId,
                    p.amount(), p.currency(), p.method(), d.paidAt());
        else log.info("[donation] webhook repetido: pagamento {} ja registrado", p.id());
        return inserted ? WebhookOutcome.RECORDED : WebhookOutcome.ALREADY_RECORDED;
    }

    /** Dev: assina e entrega uma notificacao como o Mercado Pago faria (percorre a validacao inteira). */
    public WebhookOutcome simulateWebhook(String paymentId) {
        String requestId = UUID.randomUUID().toString();
        String signature = webhookSecret == null ? null
                : WebhookSignature.header(webhookSecret, requestId, paymentId, clock.instant().getEpochSecond());
        return handleWebhook(paymentId, "payment", signature, requestId);
    }

    @Scheduled(fixedDelay = 10 * 60_000L)
    public void pruneLimiters() {
        createPerAccount.prune();
    }

    // ------------------------------------------------------------------ helpers

    private Optional<AccountRow> safeFindAccount(long id) {
        try {
            return accounts == null ? Optional.empty() : accounts.findById(id);
        } catch (RuntimeException e) {
            log.warn("[donation] nao consegui ler a conta {} no banco do jogo: {}", id, e.toString());
            return Optional.empty();
        }
    }

    private static PaymentView view(Payment p, Donation d) {
        PixView pix = p.pix() == null ? null : new PixView(p.pix().qrCode(), p.pix().qrCodeImage(), p.pix().ticketUrl(), p.pix().expiresAt());
        return new PaymentView(p.id(), p.status(), p.statusDetail(), p.method(), p.amount(), p.currency(),
                d != null, d == null ? null : d.paidAt(), d == null ? null : d.recordedAt(), pix);
    }

    private static String digits(String s) {
        return s == null ? null : s.replaceAll("\\D", "");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String safe(String s) {
        if (s == null) return "-";
        String clean = s.replaceAll("[^A-Za-z0-9:._\\-]", "?");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }
}
