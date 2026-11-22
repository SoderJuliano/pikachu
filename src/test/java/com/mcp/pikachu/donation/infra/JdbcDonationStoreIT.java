package com.mcp.pikachu.donation.infra;

import com.mcp.pikachu.donation.domain.Donation;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integracao contra um MariaDB real (so roda com DONATION_IT_DB_URL/USER/PASSWORD definidos).
 * Usa a tabela descartavel donation_it, apagada no fim.
 */
@EnabledIfEnvironmentVariable(named = "DONATION_IT_DB_URL", matches = ".+")
class JdbcDonationStoreIT {

    static HikariDataSource ds;
    static JdbcDonationStore store;

    @BeforeAll
    static void setUp() {
        ds = new HikariDataSource();
        ds.setJdbcUrl(System.getenv("DONATION_IT_DB_URL"));
        ds.setUsername(System.getenv("DONATION_IT_DB_USER"));
        ds.setPassword(System.getenv("DONATION_IT_DB_PASSWORD"));
        store = new JdbcDonationStore(ds, "donation_it");
        new JdbcTemplate(ds).execute("DROP TABLE IF EXISTS donation_it");
    }

    @AfterAll
    static void tearDown() {
        new JdbcTemplate(ds).execute("DROP TABLE IF EXISTS donation_it");
        ds.close();
    }

    @Test
    void savesOnceAndReadsBackInUtc() {
        Instant paid = Instant.parse("2026-11-22T14:15:30.123Z"), rec = Instant.parse("2026-11-22T14:15:31.456Z");
        Donation d = new Donation("1325467890", 42, "ARTHAS", "a@x.com", new BigDecimal("29.90"), "BRL", "pix", paid, rec, false);
        assertTrue(store.saveIfAbsent(d));
        assertFalse(store.saveIfAbsent(d), "reentrega do webhook nao duplica");
        assertEquals(d, store.findByPaymentId("1325467890").orElseThrow());
        assertTrue(store.findByPaymentId("999").isEmpty());

        store.saveIfAbsent(new Donation("1", 42, "ARTHAS", "a@x.com", new BigDecimal("29.90"), "BRL", "card", paid.minusSeconds(60), rec, true));
        var list = store.findByAccount(42);
        assertEquals(2, list.size());
        assertEquals("1", list.getFirst().paymentId(), "mais antiga primeiro");
        assertTrue(store.findByAccount(7).isEmpty());
    }
}
