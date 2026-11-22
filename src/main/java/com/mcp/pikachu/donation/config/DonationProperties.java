package com.mcp.pikachu.donation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * Configuracao nao-secreta do modulo de doacoes (application.yaml, prefixo {@code donation}).
 * Token do Mercado Pago e segredo do webhook ficam no secrets.txt (ver {@link DonationSecrets}).
 *
 * @param gateway         {@code mercadopago} (API real; credenciais de teste ou producao) ou {@code mock}
 *                        (tudo em memoria, sem rede: pagamentos falsos aprovados pela rota de dev).
 * @param store           {@code jdbc} (MariaDB, wowlk_site.donation) ou {@code memory} (testes locais sem banco).
 * @param devTools        libera {@code POST /donation/v1/dev/simulate-webhook/{id}}. NUNCA ligar em producao.
 * @param notificationUrl URL publica HTTPS do webhook, mandada em cada pagamento. Vazia = vale a URL
 *                        configurada no painel do Mercado Pago (Suas integracoes > Webhooks).
 * @param testPayerEmail  e-mail de comprador de teste: o sandbox do Mercado Pago recusa pagador "real".
 *                        Vazio = usa o e-mail da conta do jogo (producao).
 * @param fixedAmount     valor unico da doacao (BRL). O que o navegador mandar e ignorado. 0 = valor livre (min/max).
 * @param usdEquivalent   so exibicao para quem esta fora do Brasil: a conta do Mercado Pago e brasileira e
 *                        sempre cobra em BRL (o banco do cartao converte).
 */
@ConfigurationProperties("donation")
public record DonationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("mercadopago") String gateway,
        @DefaultValue("jdbc") String store,
        @DefaultValue("false") boolean devTools,
        @DefaultValue("https://api.mercadopago.com") String mercadoPagoBaseUrl,
        @DefaultValue("") String notificationUrl,
        @DefaultValue("") String testPayerEmail,
        @DefaultValue("BRL") String currency,
        @DefaultValue("5") BigDecimal minAmount,
        @DefaultValue("1000") BigDecimal maxAmount,
        @DefaultValue("0") BigDecimal fixedAmount,
        @DefaultValue("0") BigDecimal usdEquivalent,
        @DefaultValue("30") int pixExpirationMinutes,
        @DefaultValue("WoW Classic Plus LK - apoio ao servidor") String description,
        @DefaultValue("CLASSICPLUS") String statementDescriptor
) {
    public boolean mockGateway() {
        return "mock".equalsIgnoreCase(gateway);
    }

    public boolean hasFixedAmount() {
        return fixedAmount != null && fixedAmount.signum() > 0;
    }

    public boolean memoryStore() {
        return "memory".equalsIgnoreCase(store);
    }
}
