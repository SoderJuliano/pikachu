package com.mcp.pikachu.wowlk.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;

/**
 * Infra do modulo wowlk: pool do MariaDB do AzerothCore, agendador e CORS das rotas do launcher.
 *
 * O pool NAO conecta na subida (initializationFailTimeout=-1): banco fora do ar nao pode
 * derrubar o Pikachu inteiro (as rotas de IA seguem funcionando). O erro aparece na rota/no worker.
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties(WowlkProperties.class)
public class WowlkConfig implements WebMvcConfigurer {

    private final WowlkProperties props;

    public WowlkConfig(WowlkProperties props) {
        this.props = props;
    }

    @Bean(destroyMethod = "close")
    public DataSource wowlkDataSource() {
        HikariConfig cfg = new HikariConfig();
        String url = WowlkSecrets.dbUrl();
        if (url == null) {
            log.warn("[wowlk] WOWLK_DB_URL ausente no secrets.txt — cadastro/login do WoW vao falhar ate configurar.");
            url = "jdbc:mariadb://127.0.0.1:3306/wowlk_site";
        }
        cfg.setJdbcUrl(url);
        cfg.setUsername(WowlkSecrets.dbUser());
        cfg.setPassword(WowlkSecrets.dbPassword());
        cfg.setPoolName("wowlk");
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(5_000);
        cfg.setValidationTimeout(3_000);
        cfg.setInitializationFailTimeout(-1);
        return new HikariDataSource(cfg);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (props.corsOrigins().isEmpty()) return;
        registry.addMapping("/launcher/v1/**")
                .allowedOrigins(props.corsOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Retry-After")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
