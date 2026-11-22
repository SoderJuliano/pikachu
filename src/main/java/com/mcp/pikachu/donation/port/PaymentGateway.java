package com.mcp.pikachu.donation.port;

import com.mcp.pikachu.donation.domain.Payment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/** Porta de saida para o provedor de pagamento (Mercado Pago ou mock local). */
public interface PaymentGateway {

    /** Dados comuns a qualquer cobranca. {@code idempotencyKey} evita cobranca dupla em retry. */
    record Charge(BigDecimal amount, String description, String payerEmail, String externalReference,
                  Map<String, String> metadata, String idempotencyKey) {
    }

    /** Cartao ja tokenizado no navegador (os numeros do cartao nunca passam pelo Pikachu). */
    record Card(String token, String paymentMethodId, String issuerId, int installments,
                String identificationType, String identificationNumber) {
    }

    Payment createPix(Charge charge, int expirationMinutes);

    Payment createCard(Charge charge, Card card);

    /** Le o pagamento direto no provedor. Vazio se nao existe. */
    Optional<Payment> fetch(String paymentId);
}
