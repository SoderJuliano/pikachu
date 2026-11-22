package com.mcp.pikachu.donation.config;

import com.mcp.pikachu.donation.application.DonationService;
import com.mcp.pikachu.donation.infra.InMemoryDonationStore;
import com.mcp.pikachu.donation.infra.MercadoPagoGateway;
import com.mcp.pikachu.donation.infra.MockPaymentGateway;
import com.mcp.pikachu.donation.infra.MongoDonationStore;
import com.mcp.pikachu.donation.port.DonationStore;
import com.mcp.pikachu.donation.port.PaymentGateway;
import com.mcp.pikachu.wowlk.infra.WowlkRepository;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * Montagem do modulo de doacoes (ports & adapters): escolhe gateway (Mercado Pago ou mock) e store
 * (Mongo ou memoria) pelo application.yaml. Mongo fora do ar nao derruba a subida: o driver conecta
 * sob demanda e o erro aparece na rota/no webhook.
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

    @Bean(destroyMethod = "close")
    public MongoClient donationMongoClient(DonationProperties props) {
        String uri = DonationSecrets.mongoUri();
        if (uri == null) {
            uri = "mongodb://localhost:27017";
            if (!props.memoryStore()) log.warn("[donation] DONATION_MONGO_URI ausente — usando {}", uri);
        }
        return MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applyToClusterSettings(c -> c.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .applyToSocketSettings(s -> s.connectTimeout(5, TimeUnit.SECONDS))
                .build());
    }

    @Bean
    public DonationStore donationStore(DonationProperties props, MongoClient donationMongoClient) {
        if (props.memoryStore()) {
            log.warn("[donation] store em MEMORIA: doacoes somem ao reiniciar (so para testes locais)");
            return new InMemoryDonationStore();
        }
        log.info("[donation] doacoes no Mongo: banco '{}', collection '{}'", props.mongoDatabase(), props.mongoCollection());
        return new MongoDonationStore(donationMongoClient.getDatabase(props.mongoDatabase()).getCollection(props.mongoCollection()));
    }

    @Bean
    public DonationService donationService(PaymentGateway gateway, DonationStore store, WowlkRepository accounts, DonationProperties props) {
        if (props.devTools()) log.warn("[donation] dev-tools LIGADO: POST /donation/v1/dev/simulate-webhook/{id} aberto. Desligue em producao.");
        return new DonationService(gateway, store, accounts, props, DonationSecrets.webhookSecret(), DonationSecrets.publicKey(), Clock.systemUTC());
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
