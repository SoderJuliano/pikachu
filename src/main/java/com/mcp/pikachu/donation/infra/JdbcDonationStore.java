package com.mcp.pikachu.donation.infra;

import com.mcp.pikachu.donation.domain.Donation;
import com.mcp.pikachu.donation.port.DonationStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Doacoes confirmadas no MariaDB do AzerothCore, schema {@code wowlk_site} (o mesmo das sessoes e da
 * fila de cadastro), tabela {@code donation}. {@code payment_id} e a chave primaria: reentrega do
 * webhook cai no {@code INSERT IGNORE} e nao duplica. Datas em UTC, como o resto do wowlk_site.
 * A tabela e criada no primeiro uso: banco fora do ar nao derruba a subida.
 */
public class JdbcDonationStore implements DonationStore {

    private static final String COLS = "payment_id, account_id, username, email, amount, currency, method, paid_at, recorded_at, live_mode";

    private final JdbcTemplate jdbc;
    private final String table;
    private volatile boolean schemaReady;

    private final RowMapper<Donation> mapper = (rs, i) -> new Donation(
            rs.getString("payment_id"), rs.getLong("account_id"), rs.getString("username"), rs.getString("email"),
            rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("method"),
            instant(rs.getTimestamp("paid_at")), instant(rs.getTimestamp("recorded_at")), rs.getBoolean("live_mode"));

    public JdbcDonationStore(DataSource dataSource) {
        this(dataSource, "donation");
    }

    /** {@code table} so muda nos testes de integracao. */
    public JdbcDonationStore(DataSource dataSource, String table) {
        if (!table.matches("[a-z_]{1,32}")) throw new IllegalArgumentException("nome de tabela invalido");
        this.jdbc = new JdbcTemplate(dataSource);
        this.table = table;
    }

    @Override
    public boolean saveIfAbsent(Donation d) {
        ensureSchema();
        return jdbc.update("INSERT IGNORE INTO " + table + " (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                d.paymentId(), d.accountId(), d.username(), d.email(), d.amount(), d.currency(), d.method(),
                utc(d.paidAt()), utc(d.recordedAt()), d.liveMode()) == 1;
    }

    @Override
    public Optional<Donation> findByPaymentId(String paymentId) {
        ensureSchema();
        return jdbc.query("SELECT " + COLS + " FROM " + table + " WHERE payment_id = ?", mapper, paymentId).stream().findFirst();
    }

    @Override
    public List<Donation> findByAccount(long accountId) {
        ensureSchema();
        return jdbc.query("SELECT " + COLS + " FROM " + table + " WHERE account_id = ? ORDER BY paid_at, payment_id", mapper, accountId);
    }

    void ensureSchema() {
        if (schemaReady) return;
        synchronized (this) {
            if (schemaReady) return;
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                      payment_id  VARCHAR(32)   NOT NULL PRIMARY KEY COMMENT 'id do pagamento no Mercado Pago',
                      account_id  INT UNSIGNED  NOT NULL COMMENT 'acore_auth.account.id',
                      username    VARCHAR(32)   NULL,
                      email       VARCHAR(255)  NULL,
                      amount      DECIMAL(10,2) NOT NULL,
                      currency    CHAR(3)       NOT NULL,
                      method      VARCHAR(8)    NOT NULL COMMENT 'pix | card',
                      paid_at     DATETIME(3)   NOT NULL COMMENT 'UTC, date_approved do Mercado Pago',
                      recorded_at DATETIME(3)   NOT NULL COMMENT 'UTC, quando o webhook registrou',
                      live_mode   TINYINT(1)    NOT NULL DEFAULT 0,
                      KEY idx_account (account_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""".formatted(table));
            schemaReady = true;
        }
    }

    /** Grava o instante como DATETIME em UTC, independente do fuso da JVM/driver. */
    private static LocalDateTime utc(Instant i) {
        return i == null ? null : LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}
