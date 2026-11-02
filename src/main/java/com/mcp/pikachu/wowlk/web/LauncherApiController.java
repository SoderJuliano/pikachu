package com.mcp.pikachu.wowlk.web;

import com.mcp.pikachu.wowlk.application.AccountMailer;
import com.mcp.pikachu.wowlk.application.AccountRegistrationService;
import com.mcp.pikachu.wowlk.application.AuthService;
import com.mcp.pikachu.wowlk.application.RealmStatusService;
import com.mcp.pikachu.wowlk.config.WowlkProperties;
import com.mcp.pikachu.wowlk.domain.ApiException;
import com.mcp.pikachu.wowlk.domain.ErrorCode;
import com.mcp.pikachu.wowlk.domain.NewAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * API do launcher OLK Plus (contrato: launcher-wowlk-classic-plus/docs/API_CONTRACT.md).
 * Tudo sob /launcher/v1. Nenhuma rota aqui usa IA.
 */
@Slf4j
@RestController
@RequestMapping("/launcher/v1")
@Tag(name = "WoW Classic Plus LK", description = "Contas AzerothCore: login e cadastro do launcher/site")
public class LauncherApiController {

    private static final long MAX_AUTH_BODY = 16 * 1024;

    public record ClientInfo(String launcher_version, String platform) {
    }

    /** {@code username} (launcher) ou {@code login} (site: usuario ou e-mail). */
    public record LoginRequest(String username, String login, String password, ClientInfo client) {
    }

    public record RefreshRequest(String refresh_token) {
    }

    /** Senha em texto (HTTPS) ou salt/verifier SRP6 ja calculados, como o site faz. */
    public record RegisterRequest(String username, String email, String password, String salt, String verifier, String locale) {
    }

    public record RegisterResponse(Map<String, Object> user, String status) {
    }

    private final AuthService auth;
    private final AccountRegistrationService registration;
    private final AccountMailer mailer;
    private final WowlkProperties props;
    private final RealmStatusService realmStatus;

    public LauncherApiController(AuthService auth, AccountRegistrationService registration, AccountMailer mailer, WowlkProperties props,
                                 RealmStatusService realmStatus) {
        this.auth = auth;
        this.registration = registration;
        this.mailer = mailer;
        this.props = props;
        this.realmStatus = realmStatus;
    }

    @Operation(summary = "Estado da API do launcher")
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("service", "pikachu", "api_version", 1, "maintenance", props.maintenance(), "time", Instant.now().toString());
    }

    @Operation(summary = "Estado do reino para o painel do site (sem login)")
    @GetMapping("/status")
    public RealmStatusService.RealmStatus status() {
        return realmStatus.current();
    }

    @Operation(summary = "Login com usuario (ou e-mail) e senha; devolve access/refresh token")
    @PostMapping("/auth/login")
    public AuthService.TokenResponse login(@RequestBody(required = false) LoginRequest body, HttpServletRequest req) {
        guard(req, true);
        if (body == null) throw new ApiException(ErrorCode.VALIDATION, "Enter your username and password.");
        String identifier = body.username() != null ? body.username() : body.login();
        ClientInfo c = body.client();
        String version = c != null && c.launcher_version() != null ? c.launcher_version() : req.getHeader("X-Launcher-Version");
        return auth.login(identifier, body.password(), c == null ? null : c.platform(), version, clientIp(req));
    }

    @Operation(summary = "Renova a sessao (rotaciona o refresh token)")
    @PostMapping("/auth/refresh")
    public AuthService.TokenResponse refresh(@RequestBody(required = false) RefreshRequest body, HttpServletRequest req) {
        guard(req, true);
        return auth.refresh(body == null ? null : body.refresh_token());
    }

    @Operation(summary = "Encerra a sessao (idempotente)")
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest body, HttpServletRequest req) {
        guard(req, false);
        auth.logout(body == null ? null : body.refresh_token(), bearer(req));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Cadastro direto (cria a conta ativa na hora). O site usa a fila da Abra API.")
    @PostMapping("/auth/register")
    public ResponseEntity<RegisterResponse> register(@RequestBody(required = false) RegisterRequest body, HttpServletRequest req) {
        guard(req, true);
        if (body == null) throw new ApiException(ErrorCode.VALIDATION, "Missing registration data.");
        NewAccount account = body.password() != null
                ? NewAccount.fromPassword(body.username(), body.email(), body.password(), body.locale())
                : NewAccount.fromSrp6(body.username(), body.email(), body.salt(), body.verifier(), 2, body.locale());
        auth.checkRegisterRate(clientIp(req));
        AccountRegistrationService.Result r = registration.register(account);
        if (r.outcome() == AccountRegistrationService.Outcome.CONFLICT) {
            log.warn("[wowlk] cadastro direto RECUSADO: usuario {} ou email {} ja em uso", account.username(), AccountMailer.mask(account.email()));
            throw new ApiException(ErrorCode.ACCOUNT_EXISTS, "This username or e-mail is already in use.");
        }
        log.info("[wowlk] cadastro direto OK: conta {} (id {}) criada e ativa, email={}", account.username(), r.accountId(), AccountMailer.mask(account.email()));
        if (r.outcome() == AccountRegistrationService.Outcome.CREATED)
            mailer.sendActivated(account.email(), account.username(), account.prefersPortuguese());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(
                Map.of("id", r.accountId(), "username", account.username(), "email_verified", true), "active"));
    }

    @Operation(summary = "Manifesto de atualizacao do cliente")
    @GetMapping(value = "/manifest/{client}", produces = "application/json")
    public String manifest(@PathVariable String client, HttpServletRequest req) throws IOException {
        guard(req, false);
        auth.authenticate(bearer(req));
        // O nome vira caminho de arquivo: so aceita o formato dos ids de cliente (wow32, wow64).
        if (!client.matches("[a-z0-9]{1,16}")) throw new ApiException(ErrorCode.NOT_FOUND, "Unknown client.");
        Path file = Path.of(props.manifestDir(), client + ".json");
        if (!Files.isRegularFile(file))
            throw new ApiException(ErrorCode.NOT_FOUND, "No update manifest published for this client yet.");
        return Files.readString(file);
    }

    // ------------------------------------------------------------------ helpers

    private void guard(HttpServletRequest req, boolean authRoute) {
        if (authRoute && req.getContentLengthLong() > MAX_AUTH_BODY)
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Request too large.");
        String v = req.getHeader("X-Launcher-Version");
        if (v != null && compareVersions(v, props.minLauncherVersion()) < 0)
            throw new ApiException(ErrorCode.LAUNCHER_OUTDATED, "This launcher version is too old. Please update it.");
        if (props.maintenance())
            throw new ApiException(ErrorCode.MAINTENANCE, "The server is under maintenance.", 300L);
    }

    private static String bearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        return h != null && h.regionMatches(true, 0, "Bearer ", 0, 7) ? h.substring(7).trim() : null;
    }

    /** ngrok preenche X-Forwarded-For com o IP real do jogador. */
    static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    static int compareVersions(String a, String b) {
        String[] x = a.trim().split("[.-]"), y = b.trim().split("[.-]");
        for (int i = 0; i < 3; i++) {
            int xi = i < x.length ? parse(x[i]) : 0, yi = i < y.length ? parse(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
