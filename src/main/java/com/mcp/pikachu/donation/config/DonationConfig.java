package com.mcp.pikachu.donation.config;

import com.mcp.pikachu.donation.application.DonationService;
import com.mcp.pikachu.donation.infra.InMemoryDonationStore;
import com.mcp.pikachu.donation.infra.MercadoPagoGateway;
import com.mcp.pikachu.donation.infra.MockPaymentGateway;
import com.mcp.pikachu.donation.infra.JdbcDonationStore;
import com.mcp.pikachu.donation.port.DonationStore;
import com.mcp.pikachu.donation.port.PaymentGateway;
import com.mcp.pikachu.wowlk.application.AccountMailer;
import com.mcp.pikachu.wowlk.infra.WowlkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Montagem do modulo de doacoes (ports & adapters): escolhe gateway (Mercado Pago ou mock) e stores
 * (MariaDB wowlk_site.donation e wowlk_site.wowsixtyfordev, ou memoria) pelo application.yaml.
 * Usa o mesmo pool do modulo wowlk; as tabelas sao criadas no primeiro uso, entao banco fora do ar
 * nao derruba a subida.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(DonationProperties.class)
public class DonationConfig implements WebMvcConfigurer {

    @Bean
    public PaymentGateway donationPaymentGateway(DonationProperties props) {
        if (props.mockGateway()) {
            log.warn("[donation] gateway MOCK ativo: nenhum pagamento real e criado (so para testes locais)");
            return new MockPaymentGateway();
        }
        if (DonationSecrets.accessToken() == null)
            log.warn("[donation] DONATION_MP_ACCESS_TOKEN ausente — as rotas de doacao vao responder erro ate configurar.");
        else if (DonationSecrets.accessToken().startsWith("TEST-") || props.testPayerEmail() != null && !props.testPayerEmail().isBlank())
            log.info("[donation] Mercado Pago em modo de TESTE");
        return new MercadoPagoGateway(props.mercadoPagoBaseUrl(), DonationSecrets.accessToken(), props.notificationUrl(), props.statementDescriptor());
    }

    @Bean
    @Primary
    public DonationStore donationStore(DonationProperties props, DataSource wowlkDataSource) {
        if (props.memoryStore()) {
            log.warn("[donation] store em MEMORIA: doacoes somem ao reiniciar (so para testes locais)");
            return new InMemoryDonationStore();
        }
        log.info("[donation] doacoes no MariaDB: wowlk_site.donation");
        return new JdbcDonationStore(wowlkDataSource);
    }

    @Bean("client64DonationStore")
    public DonationStore client64DonationStore(DonationProperties props, DataSource wowlkDataSource) {
        if (props.memoryStore()) {
            log.warn("[donation] store 64-bit em MEMORIA (wowsixtyfordev): doacoes somem ao reiniciar");
            return new InMemoryDonationStore();
        }
        log.info("[donation] doacoes 64-bit no MariaDB: wowlk_site.wowsixtyfordev");
        return new JdbcDonationStore(wowlkDataSource, "wowsixtyfordev");
    }

    @Bean
    public DonationService donationService(PaymentGateway gateway,
                                           DonationStore store,
                                           @Qualifier("client64DonationStore") DonationStore client64Store,
                                           WowlkRepository accounts,
                                           AccountMailer mailer,
                                           DonationProperties props) {
        if (props.devTools()) log.warn("[donation] dev-tools LIGADO: POST /donation/v1/dev/simulate-webhook/{id} aberto. Desligue em producao.");
        return new DonationService(gateway, store, client64Store, accounts, mailer, props,
                DonationSecrets.webhookSecret(), DonationSecrets.publicKey(), Clock.systemUTC());
    }

    /** O site chama de qualquer dominio; sem cookies (Bearer no cabecalho), igual ao launcher. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/donation/v1/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Retry-After")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
