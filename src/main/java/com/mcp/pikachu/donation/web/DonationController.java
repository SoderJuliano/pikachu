package com.mcp.pikachu.donation.web;

import com.mcp.pikachu.donation.application.DonationService;
import com.mcp.pikachu.donation.config.DonationProperties;
import com.mcp.pikachu.donation.infra.MockPaymentGateway;
import com.mcp.pikachu.donation.port.PaymentGateway;
import com.mcp.pikachu.wowlk.application.AuthService;
import com.mcp.pikachu.wowlk.domain.AccountRow;
import com.mcp.pikachu.wowlk.domain.ApiException;
import com.mcp.pikachu.wowlk.domain.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Doacoes do WoW Classic Plus LK (Mercado Pago). Tudo sob /donation/v1.
 * <ul>
 *   <li>Rotas do jogador: exigem o Bearer do login do site/launcher ({@code /launcher/v1/auth/*}).</li>
 *   <li>{@code POST /webhooks/mercadopago}: publica, chamada pelo Mercado Pago (valida x-signature).</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/donation/v1")
@Tag(name = "Doacoes WoW Classic Plus LK", description = "Pix (Brasil) e cartao (outros paises) via Mercado Pago; webhook registra no Mongo")
public class DonationController {

    private static final long MAX_BODY = 8 * 1024;

    public record PixRequest(BigDecimal amount) {
    }

    public record CardRequest(BigDecimal amount, String token, String payment_method_id, String issuer_id,
                              String identification_type, String identification_number) {
    }

    private final DonationService donations;
    private final AuthService auth;
    private final DonationProperties props;
    private final PaymentGateway gateway;

    public DonationController(DonationService donations, AuthService auth, DonationProperties props, PaymentGateway gateway) {
        this.donations = donations;
        this.auth = auth;
        this.props = props;
        this.gateway = gateway;
    }

    @Operation(summary = "Configuracao publica para o site (public key do Mercado Pago, limites de valor)")
    @GetMapping("/config")
    public DonationService.PublicConfig config() {
        enabled();
        return donations.publicConfig();
    }

    @Operation(summary = "Cria uma cobranca Pix e devolve o QR Code (copia e cola + imagem)")
    @PostMapping("/payments/pix")
    public ResponseEntity<DonationService.PaymentView> pix(@RequestBody(required = false) PixRequest body, HttpServletRequest req,
                                                           @RequestHeader(value = "X-Idempotency-Key", required = false) String idem) {
        AccountRow account = account(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(donations.createPix(account, body == null ? null : body.amount(), idem));
    }

    @Operation(summary = "Paga com cartao ja tokenizado pelo MercadoPago.js no navegador")
    @PostMapping("/payments/card")
    public ResponseEntity<DonationService.PaymentView> card(@RequestBody(required = false) CardRequest body, HttpServletRequest req,
                                                            @RequestHeader(value = "X-Idempotency-Key", required = false) String idem) {
        AccountRow account = account(req);
        if (body == null) throw new ApiException(ErrorCode.VALIDATION, "Card data is incomplete.");
        PaymentGateway.Card card = new PaymentGateway.Card(body.token(), body.payment_method_id(), body.issuer_id(), 1,
                body.identification_type(), body.identification_number());
        return ResponseEntity.status(HttpStatus.CREATED).body(donations.createCard(account, body.amount(), card, idem));
    }

    @Operation(summary = "Status do pagamento (o site consulta ate recorded=true, gravado pelo webhook)")
    @GetMapping("/payments/{id}")
    public DonationService.PaymentView status(@PathVariable String id, HttpServletRequest req) {
        return donations.status(account(req), id);
    }

    @Operation(summary = "Selo de apoiador da conta logada")
    @GetMapping("/me")
    public DonationService.SupporterView me(HttpServletRequest req) {
        return donations.supporter(account(req));
    }

    /**
     * URL para cadastrar no painel do Mercado Pago (Suas integracoes > Webhooks > evento "Pagamentos").
     * Sempre responde 200 rapido quando a notificacao e legitima (senao o Mercado Pago reenvia);
     * assinatura invalida = 401.
     */
    @Operation(summary = "Webhook do Mercado Pago (publico)")
    @PostMapping("/webhooks/mercadopago")
    public ResponseEntity<Map<String, String>> webhook(
            @RequestParam(value = "data.id", required = false) String dataId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "id", required = false) String legacyId,
            @RequestParam(value = "topic", required = false) String legacyTopic,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "x-signature", required = false) String signature,
            @RequestHeader(value = "x-request-id", required = false) String requestId) {
        String id = dataId != null ? dataId : legacyId;
        String kind = type != null ? type : legacyTopic;
        if (id == null && body != null && body.get("data") instanceof Map<?, ?> data && data.get("id") != null)
            id = String.valueOf(data.get("id"));
        if (kind == null && body != null && body.get("type") != null) kind = String.valueOf(body.get("type"));
        DonationService.WebhookOutcome outcome = donations.handleWebhook(id, kind, signature, requestId);
        if (outcome == DonationService.WebhookOutcome.INVALID_SIGNATURE)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("result", outcome.name()));
        return ResponseEntity.ok(Map.of("result", outcome.name()));
    }

    /**
     * SO DESENVOLVIMENTO ({@code donation.dev-tools: true}): faz o papel do Mercado Pago chamando o webhook
     * assinado. No gateway mock, antes "paga" o Pix. Com o Mercado Pago real (teste), serve para quando o
     * webhook nao alcanca o localhost: o pagamento continua sendo relido na API deles.
     */
    @Operation(summary = "[dev] Simula a entrega do webhook para um pagamento")
    @PostMapping("/dev/simulate-webhook/{id}")
    public Map<String, String> simulate(@PathVariable String id) {
        if (!props.devTools()) throw new ApiException(ErrorCode.NOT_FOUND, "Not found.");
        if (gateway instanceof MockPaymentGateway mock && !mock.approve(id))
            throw new ApiException(ErrorCode.NOT_FOUND, "Payment not found.");
        return Map.of("result", donations.simulateWebhook(id).name());
    }

    // ------------------------------------------------------------------ helpers

    private AccountRow account(HttpServletRequest req) {
        enabled();
        if (req.getContentLengthLong() > MAX_BODY) throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Request too large.");
        String h = req.getHeader("Authorization");
        String token = h != null && h.regionMatches(true, 0, "Bearer ", 0, 7) ? h.substring(7).trim() : null;
        return auth.authenticate(token);
    }

    private void enabled() {
        if (!props.enabled()) throw new ApiException(ErrorCode.MAINTENANCE, "Donations are temporarily unavailable.", 300L);
    }
}
