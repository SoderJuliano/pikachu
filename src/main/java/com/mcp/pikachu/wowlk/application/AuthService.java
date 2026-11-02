package com.mcp.pikachu.wowlk.application;

import com.mcp.pikachu.wowlk.config.WowlkProperties;
import com.mcp.pikachu.wowlk.domain.AccountRow;
import com.mcp.pikachu.wowlk.domain.ApiException;
import com.mcp.pikachu.wowlk.domain.ErrorCode;
import com.mcp.pikachu.wowlk.domain.NewAccount;
import com.mcp.pikachu.wowlk.domain.Srp6;
import com.mcp.pikachu.wowlk.infra.RateLimiter;
import com.mcp.pikachu.wowlk.infra.WowlkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Login do launcher (contrato docs/API_CONTRACT.md §4) contra acore_auth.account via SRP6.
 * Tokens opacos (32 bytes aleatorios), guardados so como SHA-256 em wowlk_site.auth_session.
 */
@Slf4j
@Service
public class AuthService {

    public record UserInfo(long id, String username, boolean email_verified) {
    }

    public record TokenResponse(String access_token, String token_type, long expires_in, String refresh_token,
                                long refresh_expires_in, UserInfo user) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] DUMMY_SALT = new byte[32];
    private static final byte[] DUMMY_VERIFIER = new byte[32];

    private final WowlkRepository repo;
    private final WowlkProperties props;
    private final RateLimiter loginPerIp;
    private final RateLimiter failuresPerUser;
    private final RateLimiter registerPerIp;

    public AuthService(WowlkRepository repo, WowlkProperties props) {
        this.repo = repo;
        this.props = props;
        Clock clock = Clock.systemUTC();
        this.loginPerIp = new RateLimiter(10, 60_000, clock);
        this.failuresPerUser = new RateLimiter(5, 15 * 60_000, clock);
        this.registerPerIp = new RateLimiter(5, 60 * 60_000, clock);
    }

    public TokenResponse login(String identifier, String password, String platform, String launcherVersion, String ip) {
        String id = identifier == null ? "" : identifier.trim();
        if (id.isEmpty() || id.length() > 254 || (!id.contains("@") && id.length() > 32)
                || password == null || password.isEmpty() || password.length() > 64)
            throw new ApiException(ErrorCode.VALIDATION, "Enter your username and password.");

        long wait = loginPerIp.hit("ip:" + ip);
        if (wait > 0) throw new ApiException(ErrorCode.RATE_LIMITED, "Too many attempts. Please wait a few minutes.", wait);
        String userKey = "u:" + id.toUpperCase();
        wait = failuresPerUser.blockedFor(userKey);
        if (wait > 0) throw new ApiException(ErrorCode.RATE_LIMITED, "Too many failed attempts. Please wait a few minutes.", wait);

        Optional<AccountRow> found = id.contains("@")
                ? repo.findByEmail(NewAccount.normalizeEmail(id))
                : repo.findByUsername(NewAccount.normalizeUsername(id));
        // Mesmo trabalho com ou sem conta: tempo de resposta nao revela se o usuario existe.
        boolean ok = found.map(a -> Srp6.matches(a.username(), password, a.salt(), a.verifier()))
                .orElseGet(() -> Srp6.matches(id, password, DUMMY_SALT, DUMMY_VERIFIER) && false);
        if (!ok) {
            failuresPerUser.hit(userKey);
            log.info("[wowlk] login recusado para '{}' (ip {})", safe(id), ip);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid username or password.");
        }
        failuresPerUser.reset(userKey);
        AccountRow account = found.get();
        assertCanPlay(account);

        Tokens t = newTokens();
        repo.createSession(account.id(), t.accessHash, props.accessTokenTtlSeconds(), t.refreshHash, props.refreshTokenTtlSeconds(),
                platform, launcherVersion, ip);
        log.info("[wowlk] login OK: {} (id {}) plataforma={} launcher={}", account.username(), account.id(), safe(platform), safe(launcherVersion));
        return response(account, t);
    }

    public TokenResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 200)
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "Your session has expired. Please sign in again.");
        byte[] oldHash = sha256(refreshToken);
        WowlkRepository.SessionRow s = repo.findByRefresh(oldHash)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "Your session has expired. Please sign in again."));
        AccountRow account = repo.findById(s.accountId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "Your session has expired. Please sign in again."));
        try {
            assertCanPlay(account);
        } catch (ApiException e) {
            repo.revokeSession(s.id());
            throw e;
        }
        Tokens t = newTokens();
        if (!repo.rotate(s.id(), oldHash, t.accessHash, props.accessTokenTtlSeconds(), t.refreshHash, props.refreshTokenTtlSeconds()))
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "Your session has expired. Please sign in again.");
        return response(account, t);
    }

    /** Idempotente: token desconhecido tambem e "ok". */
    public void logout(String refreshToken, String accessToken) {
        byte[] r = refreshToken == null || refreshToken.isBlank() ? new byte[32] : sha256(refreshToken);
        byte[] a = accessToken == null || accessToken.isBlank() ? new byte[32] : sha256(accessToken);
        repo.revokeByRefreshOrAccess(r, a);
    }

    /** Valida o Bearer das rotas autenticadas e devolve a conta. */
    public AccountRow authenticate(String accessToken) {
        if (accessToken == null || accessToken.isBlank())
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Please sign in again.");
        return repo.findByAccess(sha256(accessToken))
                .flatMap(s -> repo.findById(s.accountId()))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Please sign in again."));
    }

    public void checkRegisterRate(String ip) {
        long wait = registerPerIp.hit("ip:" + ip);
        if (wait > 0) throw new ApiException(ErrorCode.RATE_LIMITED, "Too many registrations. Please try again later.", wait);
    }

    @Scheduled(fixedDelay = 10 * 60_000L)
    public void pruneLimiters() {
        loginPerIp.prune();
        failuresPerUser.prune();
        registerPerIp.prune();
    }

    private void assertCanPlay(AccountRow account) {
        Optional<Long> ban = repo.activeBanUntil(account.id());
        if (ban.isPresent()) {
            long until = ban.get();
            throw new ApiException(ErrorCode.ACCOUNT_BANNED, until == 0
                    ? "This account is banned."
                    : "This account is banned until " + Instant.ofEpochSecond(until) + ".");
        }
        if (account.locked()) throw new ApiException(ErrorCode.ACCOUNT_LOCKED, "This account is locked.");
    }

    private TokenResponse response(AccountRow a, Tokens t) {
        return new TokenResponse(t.access, "Bearer", props.accessTokenTtlSeconds(), t.refresh, props.refreshTokenTtlSeconds(),
                new UserInfo(a.id(), a.username(), true));
    }

    private record Tokens(String access, byte[] accessHash, String refresh, byte[] refreshHash) {
    }

    private static Tokens newTokens() {
        String access = "a_" + randomToken();
        String refresh = "r_" + randomToken();
        return new Tokens(access, sha256(access), refresh, sha256(refresh));
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String safe(String s) {
        if (s == null) return "-";
        String clean = s.replaceAll("[^A-Za-z0-9@._\\-]", "?");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }
}
