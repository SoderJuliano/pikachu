package com.mcp.pikachu.donation.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcp.pikachu.donation.domain.Payment;
import com.mcp.pikachu.donation.port.PaymentGateway;
import com.mcp.pikachu.wowlk.domain.ApiException;
import com.mcp.pikachu.wowlk.domain.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Adaptador da API de pagamentos do Mercado Pago (Checkout Transparente, {@code /v1/payments}).
 * Cliente HTTP puro (sem SDK): so tres chamadas. Credenciais de teste e producao usam a mesma URL;
 * o que muda e o Access Token.
 */
@Slf4j
public class MercadoPagoGateway implements PaymentGateway {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final DateTimeFormatter MP_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    /** Horario de Brasilia, como o painel do Mercado Pago mostra. */
    private static final ZoneOffset BRT = ZoneOffset.ofHours(-3);

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String accessToken;
    private final String notificationUrl;
    private final String statementDescriptor;

    public MercadoPagoGateway(HttpClient http, String baseUrl, String accessToken, String notificationUrl, String statementDescriptor) {
        this.http = http;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.accessToken = accessToken;
        this.notificationUrl = notificationUrl == null || notificationUrl.isBlank() ? null : notificationUrl.trim();
        this.statementDescriptor = statementDescriptor;
    }

    public MercadoPagoGateway(String baseUrl, String accessToken, String notificationUrl, String statementDescriptor) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), baseUrl, accessToken, notificationUrl, statementDescriptor);
    }

    @Override
    public Payment createPix(Charge charge, int expirationMinutes) {
        try {
            return createOrderPix(charge, expirationMinutes);
        } catch (ApiException e) {
            log.warn("[donation] criacao via /v1/orders falhou ({}), tentando /v1/payments", e.getMessage());
            ObjectNode body = base(charge);
            body.put("payment_method_id", "pix");
            body.put("date_of_expiration", OffsetDateTime.now(BRT).plusMinutes(expirationMinutes).format(MP_DATE));
            return create(body, charge.idempotencyKey());
        }
    }

    private Payment createOrderPix(Charge charge, int expirationMinutes) {
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "online");
        body.put("processing_mode", "automatic");
        body.put("total_amount", charge.amount().setScale(2, java.math.RoundingMode.HALF_UP).toString());
        body.put("external_reference", charge.externalReference());
        body.put("description", charge.description());
        if (notificationUrl != null) body.put("notification_url", notificationUrl);
        ObjectNode payer = body.putObject("payer");
        payer.put("email", charge.payerEmail());
        payer.put("first_name", "Apoiador");
        payer.put("last_name", "WoW");

        ObjectNode tx = body.putObject("transactions");
        var payments = tx.putArray("payments");
        ObjectNode p0 = payments.addObject();
        p0.put("amount", charge.amount().setScale(2, java.math.RoundingMode.HALF_UP).toString());
        ObjectNode pm = p0.putObject("payment_method");
        pm.put("id", "pix");
        pm.put("type", "bank_transfer");

        HttpRequest req = request("/v1/orders")
                .header("Content-Type", "application/json")
                .header("X-Idempotency-Key", charge.idempotencyKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res = send(req);
        if (res.statusCode() / 100 == 2) return parseOrder(res.body(), charge);
        log.warn("[donation] Mercado Pago recusou criacao da order: HTTP {} {}", res.statusCode(), abbreviate(res.body()));
        if (res.statusCode() == 400 || res.statusCode() == 422)
            throw new ApiException(ErrorCode.VALIDATION, "The payment provider refused the payment data. Check the details and try again.");
        throw new ApiException(ErrorCode.INTERNAL, "Payment provider unavailable. Try again in a moment.");
    }

    @Override
    public Payment createCard(Charge charge, Card card) {
        ObjectNode body = base(charge);
        body.put("token", card.token());
        body.put("payment_method_id", card.paymentMethodId());
        body.put("installments", Math.max(1, card.installments()));
        if (card.issuerId() != null && !card.issuerId().isBlank()) body.put("issuer_id", card.issuerId());
        if (statementDescriptor != null && !statementDescriptor.isBlank()) body.put("statement_descriptor", statementDescriptor);
        if (card.identificationNumber() != null && !card.identificationNumber().isBlank()) {
            ObjectNode id = ((ObjectNode) body.get("payer")).putObject("identification");
            id.put("type", card.identificationType() == null || card.identificationType().isBlank() ? "CPF" : card.identificationType());
            id.put("number", card.identificationNumber());
        }
        return create(body, charge.idempotencyKey());
    }

    @Override
    public Optional<Payment> fetch(String paymentId) {
        if (paymentId == null || paymentId.isBlank()) return Optional.empty();
        if (paymentId.startsWith("ORD")) {
            HttpRequest req = request("/v1/orders/" + paymentId).GET().build();
            HttpResponse<String> res = send(req);
            if (res.statusCode() == 404) return Optional.empty();
            if (res.statusCode() / 100 != 2) {
                log.error("[donation] Mercado Pago GET /v1/orders/{} HTTP {}: {}", paymentId, res.statusCode(), abbreviate(res.body()));
                throw new ApiException(ErrorCode.INTERNAL, "Payment provider unavailable. Try again in a moment.");
            }
            return Optional.of(parseOrder(res.body(), null));
        }
        if (!paymentId.matches("\\d{1,24}")) return Optional.empty();
        HttpRequest req = request("/v1/payments/" + paymentId).GET().build();
        HttpResponse<String> res = send(req);
        if (res.statusCode() == 404) return Optional.empty();
        if (res.statusCode() / 100 != 2) {
            log.error("[donation] Mercado Pago GET /v1/payments/{} HTTP {}: {}", paymentId, res.statusCode(), abbreviate(res.body()));
            throw new ApiException(ErrorCode.INTERNAL, "Payment provider unavailable. Try again in a moment.");
        }
        return Optional.of(parse(res.body()));
    }

    Payment parseOrder(String json, Charge charge) {
        try {
            JsonNode n = mapper.readTree(json);
            String orderId = text(n, "id");
            JsonNode pay = n.path("transactions").path("payments").path(0);
            String status = text(n, "status");
            if ("action_required".equals(status)) status = "pending";
            if ("processed".equals(status) || "paid".equals(status)) status = "approved";
            String statusDetail = text(n, "status_detail");

            Payment.PixData pix = null;
            JsonNode pm = pay.path("payment_method");
            if (pm.hasNonNull("qr_code")) {
                String b64 = text(pm, "qr_code_base64");
                pix = new Payment.PixData(
                        text(pm, "qr_code"),
                        b64 == null ? null : (b64.startsWith("data:") ? b64 : "data:image/png;base64," + b64),
                        text(pm, "ticket_url"),
                        instant(text(pay, "date_of_expiration")));
            }
            Map<String, String> meta = charge != null ? charge.metadata() : Map.of();
            return new Payment(
                    orderId,
                    status,
                    statusDetail,
                    "pix",
                    n.hasNonNull("total_amount") ? new BigDecimal(n.get("total_amount").asText()) : BigDecimal.ZERO,
                    text(n, "currency") != null ? text(n, "currency") : "BRL",
                    text(n, "external_reference"),
                    instant(text(n, "last_updated_date")),
                    false,
                    meta,
                    pix);
        } catch (Exception e) {
            log.error("[donation] erro ao parsear order: {}", e.getMessage());
            throw new ApiException(ErrorCode.INTERNAL, "Unexpected answer from the payment provider.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode base(Charge charge) {
        ObjectNode body = mapper.createObjectNode();
        body.put("transaction_amount", charge.amount());
        body.put("description", charge.description());
        body.put("external_reference", charge.externalReference());
        if (notificationUrl != null) body.put("notification_url", notificationUrl);
        body.putObject("payer").put("email", charge.payerEmail());
        ObjectNode meta = body.putObject("metadata");
        charge.metadata().forEach(meta::put);
        return body;
    }

    private Payment create(ObjectNode body, String idempotencyKey) {
        HttpRequest req = request("/v1/payments")
                .header("Content-Type", "application/json")
                .header("X-Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res = send(req);
        if (res.statusCode() / 100 == 2) return parse(res.body());
        log.warn("[donation] Mercado Pago recusou a criacao do pagamento: HTTP {} {}", res.statusCode(), abbreviate(res.body()));
        if (res.statusCode() == 400 || res.statusCode() == 422)
            throw new ApiException(ErrorCode.VALIDATION, "The payment provider refused the payment data. Check the details and try again.");
        throw new ApiException(ErrorCode.INTERNAL, "Payment provider unavailable. Try again in a moment.");
    }

    private HttpRequest.Builder request(String path) {
        if (accessToken == null)
            throw new ApiException(ErrorCode.INTERNAL, "Donations are not configured on the server yet.");
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json");
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.error("[donation] falha de rede com o Mercado Pago: {}", e.toString());
            throw new ApiException(ErrorCode.INTERNAL, "Payment provider unavailable. Try again in a moment.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.INTERNAL, "Payment provider unavailable. Try again in a moment.");
        }
    }

    Payment parse(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            String methodId = text(n, "payment_method_id");
            Payment.PixData pix = null;
            JsonNode td = n.path("point_of_interaction").path("transaction_data");
            if (td.hasNonNull("qr_code")) {
                String b64 = text(td, "qr_code_base64");
                pix = new Payment.PixData(text(td, "qr_code"), b64 == null ? null : "data:image/png;base64," + b64,
                        text(td, "ticket_url"), instant(text(n, "date_of_expiration")));
            }
            Map<String, String> meta = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = n.path("metadata").properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getValue().isNull()) meta.put(e.getKey(), e.getValue().asText());
            }
            return new Payment(
                    n.path("id").asText(),
                    text(n, "status"),
                    text(n, "status_detail"),
                    "pix".equals(methodId) ? "pix" : "card",
                    n.hasNonNull("transaction_amount") ? n.get("transaction_amount").decimalValue() : BigDecimal.ZERO,
                    text(n, "currency_id"),
                    text(n, "external_reference"),
                    instant(text(n, "date_approved")),
                    n.path("live_mode").asBoolean(false),
                    meta,
                    pix);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Unexpected answer from the payment provider.");
        }
    }

    private static Instant instant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 600 ? s.substring(0, 600) + "..." : s;
    }
}
