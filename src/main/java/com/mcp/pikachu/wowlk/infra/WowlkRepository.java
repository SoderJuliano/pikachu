package com.mcp.pikachu.wowlk.infra;

import com.mcp.pikachu.wowlk.domain.AccountRow;
import com.mcp.pikachu.wowlk.domain.NewAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * Acesso ao MariaDB do AzerothCore.
 * - {@code acore_auth.account}: SELECT, INSERT e UPDATE so de salt/verifier (o usuario pikachu_wowlk nao tem mais que isso).
 * - schema proprio {@code wowlk_site}: sessoes do launcher e historico do processamento da fila.
 * Contas sao criadas SEM linha em account_access (= jogador comum, sem GM/admin).
 */
@Slf4j
@Repository
public class WowlkRepository {

    private static final RowMapper<AccountRow> ACCOUNT = (rs, i) -> new AccountRow(
            rs.getLong("id"), rs.getString("username"), rs.getString("email"),
            rs.getBytes("salt"), rs.getBytes("verifier"), rs.getInt("locked") != 0);

    private static final String ACCOUNT_COLS = "id, username, email, salt, verifier, locked";

    private final JdbcTemplate jdbc;
    private volatile boolean schemaReady;

    public WowlkRepository(DataSource wowlkDataSource) {
        this.jdbc = new JdbcTemplate(wowlkDataSource);
    }

    /** Fail-fast do worker: se o banco nao responde, nem tenta processar a fila. */
    public void ping() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        ensureSchema();
    }

    public void ensureSchema() {
        if (schemaReady) return;
        synchronized (this) {
            if (schemaReady) return;
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS registration_event (
                      payload_id      VARCHAR(64)  NOT NULL PRIMARY KEY,
                      notification_id VARCHAR(64)  NOT NULL,
                      username        VARCHAR(32)  NULL,
                      email           VARCHAR(255) NULL,
                      status          VARCHAR(16)  NOT NULL,
                      attempts        INT          NOT NULL DEFAULT 0,
                      last_error      VARCHAR(500) NULL,
                      account_id      INT UNSIGNED NULL,
                      created_at      DATETIME     NOT NULL DEFAULT UTC_TIMESTAMP(),
                      updated_at      DATETIME     NOT NULL DEFAULT UTC_TIMESTAMP()
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS auth_session (
                      id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      account_id         INT UNSIGNED NOT NULL,
                      access_hash        BINARY(32)   NOT NULL,
                      access_expires_at  DATETIME     NOT NULL,
                      refresh_hash       BINARY(32)   NOT NULL,
                      refresh_expires_at DATETIME     NOT NULL,
                      revoked_at         DATETIME     NULL,
                      platform           VARCHAR(16)  NULL,
                      launcher_version   VARCHAR(32)  NULL,
                      ip                 VARCHAR(45)  NULL,
                      created_at         DATETIME     NOT NULL DEFAULT UTC_TIMESTAMP(),
                      updated_at         DATETIME     NOT NULL DEFAULT UTC_TIMESTAMP(),
                      UNIQUE KEY uk_access (access_hash),
                      UNIQUE KEY uk_refresh (refresh_hash),
                      KEY idx_account (account_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
            schemaReady = true;
        }
    }

    // ---------------------------------------------------------------- account

    public Optional<AccountRow> findByUsername(String username) {
        return jdbc.query("SELECT " + ACCOUNT_COLS + " FROM acore_auth.account WHERE username = ?", ACCOUNT, username)
                .stream().findFirst();
    }

    public Optional<AccountRow> findByEmail(String email) {
        return jdbc.query("SELECT " + ACCOUNT_COLS + " FROM acore_auth.account WHERE LOWER(email) = ? ORDER BY id LIMIT 1",
                ACCOUNT, email).stream().findFirst();
    }

    public Optional<AccountRow> findById(long id) {
        return jdbc.query("SELECT " + ACCOUNT_COLS + " FROM acore_auth.account WHERE id = ?", ACCOUNT, id).stream().findFirst();
    }

    /** Conta que ja usa o nome OU o e-mail (o e-mail nao tem indice unico no AzerothCore). */
    public List<AccountRow> findConflicts(String username, String email) {
        return jdbc.query("SELECT " + ACCOUNT_COLS + " FROM acore_auth.account WHERE username = ? OR LOWER(email) = ? OR LOWER(reg_mail) = ?",
                ACCOUNT, username, email, email);
    }

    /** Conta ativa (locked=0) direto: o cadastro libera o jogo assim que a fila e processada. */
    public long insertAccount(NewAccount a) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO acore_auth.account (username, salt, verifier, email, reg_mail, expansion, locked, joindate)
                    VALUES (?, ?, ?, ?, ?, 2, 0, NOW())""", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, a.username());
            ps.setBytes(2, a.salt());
            ps.setBytes(3, a.verifier());
            ps.setString(4, a.email());
            ps.setString(5, a.email());
            return ps;
        }, keys);
        Number id = keys.getKey();
        return id != null ? id.longValue() : findByUsername(a.username()).orElseThrow().id();
    }

    /** Ban ativo: permanente (unbandate = bandate) ou ainda dentro do prazo. Retorna o unbandate (epoch s) ou vazio. */
    public Optional<Long> activeBanUntil(long accountId) {
        return jdbc.query("""
                        SELECT bandate, unbandate FROM acore_auth.account_banned
                        WHERE id = ? AND active = 1 AND (unbandate = bandate OR unbandate > UNIX_TIMESTAMP())
                        ORDER BY unbandate = bandate DESC, unbandate DESC LIMIT 1""",
                (rs, i) -> rs.getLong("unbandate") == rs.getLong("bandate") ? 0L : rs.getLong("unbandate"),
                accountId).stream().findFirst();
    }

    /** GM/admin em qualquer realm: fica de fora da troca de senha pelo site. */
    public boolean isPrivileged(long accountId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM acore_auth.account_access WHERE id = ? AND gmlevel > 0", Integer.class, accountId);
        return n != null && n > 0;
    }

    public void updatePassword(long accountId, byte[] salt, byte[] verifier) {
        jdbc.update("UPDATE acore_auth.account SET salt = ?, verifier = ? WHERE id = ?", salt, verifier, accountId);
    }

    /** Contas de gente: sem os bots, as contas de teste AGENTE* e a linha ADM, que so reserva o nome. */
    private static final String HUMANAS = "username NOT LIKE 'RNDBOT%' AND username NOT LIKE 'AGENTE%' AND username <> 'ADM'";

    public int countHumanAccounts() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM acore_auth.account WHERE " + HUMANAS, Integer.class);
    }

    public int countHumansOnline() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM acore_auth.account WHERE online <> 0 AND " + HUMANAS, Integer.class);
    }

    // ---------------------------------------------------------------- registration_event

    public Optional<String> registrationStatus(String payloadId) {
        ensureSchema();
        return jdbc.query("SELECT status FROM registration_event WHERE payload_id = ?", (rs, i) -> rs.getString(1), payloadId)
                .stream().findFirst();
    }

    public void saveRegistrationEvent(String payloadId, String notificationId, String username, String email,
                                      String status, int attempts, String lastError, Long accountId) {
        ensureSchema();
        jdbc.update("""
                        INSERT INTO registration_event (payload_id, notification_id, username, email, status, attempts, last_error, account_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE status = VALUES(status), attempts = VALUES(attempts),
                          last_error = VALUES(last_error), account_id = COALESCE(VALUES(account_id), account_id),
                          updated_at = UTC_TIMESTAMP()""",
                payloadId, notificationId, trim(username, 32), trim(email, 255), status, attempts, trim(lastError, 500), accountId);
    }

    // ---------------------------------------------------------------- auth_session

    public record SessionRow(long id, long accountId) {
    }

    public void createSession(long accountId, byte[] accessHash, long accessTtl, byte[] refreshHash, long refreshTtl,
                              String platform, String launcherVersion, String ip) {
        ensureSchema();
        jdbc.update("""
                        INSERT INTO auth_session (account_id, access_hash, access_expires_at, refresh_hash, refresh_expires_at, platform, launcher_version, ip)
                        VALUES (?, ?, DATE_ADD(UTC_TIMESTAMP(), INTERVAL ? SECOND), ?, DATE_ADD(UTC_TIMESTAMP(), INTERVAL ? SECOND), ?, ?, ?)""",
                accountId, accessHash, accessTtl, refreshHash, refreshTtl, trim(platform, 16), trim(launcherVersion, 32), trim(ip, 45));
    }

    public Optional<SessionRow> findByRefresh(byte[] refreshHash) {
        ensureSchema();
        return jdbc.query("""
                        SELECT id, account_id FROM auth_session
                        WHERE refresh_hash = ? AND revoked_at IS NULL AND refresh_expires_at > UTC_TIMESTAMP()""",
                (rs, i) -> new SessionRow(rs.getLong(1), rs.getLong(2)), refreshHash).stream().findFirst();
    }

    public Optional<SessionRow> findByAccess(byte[] accessHash) {
        ensureSchema();
        return jdbc.query("""
                        SELECT id, account_id FROM auth_session
                        WHERE access_hash = ? AND revoked_at IS NULL AND access_expires_at > UTC_TIMESTAMP()""",
                (rs, i) -> new SessionRow(rs.getLong(1), rs.getLong(2)), accessHash).stream().findFirst();
    }

    /**
     * Rotaciona os dois tokens de forma atomica: so atualiza se o refresh ainda e o mesmo
     * (dois refresh simultaneos com o mesmo token → apenas um vence, o outro recebe 401).
     */
    public boolean rotate(long sessionId, byte[] oldRefreshHash, byte[] accessHash, long accessTtl, byte[] refreshHash, long refreshTtl) {
        return jdbc.update("""
                        UPDATE auth_session SET access_hash = ?, access_expires_at = DATE_ADD(UTC_TIMESTAMP(), INTERVAL ? SECOND),
                          refresh_hash = ?, refresh_expires_at = DATE_ADD(UTC_TIMESTAMP(), INTERVAL ? SECOND), updated_at = UTC_TIMESTAMP()
                        WHERE id = ? AND refresh_hash = ? AND revoked_at IS NULL""",
                accessHash, accessTtl, refreshHash, refreshTtl, sessionId, oldRefreshHash) == 1;
    }

    public void revokeByRefreshOrAccess(byte[] refreshHash, byte[] accessHash) {
        ensureSchema();
        jdbc.update("UPDATE auth_session SET revoked_at = UTC_TIMESTAMP() WHERE revoked_at IS NULL AND (refresh_hash = ? OR access_hash = ?)",
                refreshHash, accessHash);
    }

    public void revokeSession(long sessionId) {
        jdbc.update("UPDATE auth_session SET revoked_at = UTC_TIMESTAMP() WHERE id = ? AND revoked_at IS NULL", sessionId);
    }

    /** Senha trocada: derruba todas as sessoes do launcher dessa conta. */
    public void revokeAllSessions(long accountId) {
        ensureSchema();
        jdbc.update("UPDATE auth_session SET revoked_at = UTC_TIMESTAMP() WHERE account_id = ? AND revoked_at IS NULL", accountId);
    }

    public int purgeExpiredSessions() {
        ensureSchema();
        return jdbc.update("DELETE FROM auth_session WHERE refresh_expires_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 DAY) "
                + "OR revoked_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 DAY)");
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
