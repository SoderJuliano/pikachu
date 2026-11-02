package com.mcp.pikachu.wowlk.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcp.pikachu.wowlk.config.WowlkProperties;
import com.mcp.pikachu.wowlk.domain.AccountRow;
import com.mcp.pikachu.wowlk.domain.ApiException;
import com.mcp.pikachu.wowlk.domain.NewAccount;
import com.mcp.pikachu.wowlk.infra.AbraClient;
import com.mcp.pikachu.wowlk.infra.AbraClient.QueueMessage;
import com.mcp.pikachu.wowlk.infra.RateLimiter;
import com.mcp.pikachu.wowlk.infra.WowlkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.mcp.pikachu.wowlk.application.AccountMailer.mask;

/**
 * Troca de senha pelo site, evento {@code password.recover} da fila de eventos.
 *
 * A prova de dono e usuario + e-mail da conta (decisao do dono do servidor: fluxo simples,
 * sem link por e-mail). Por isso: GM/admin ficam de fora, ha limite por usuario, e o
 * site nunca sabe se os dados bateram. A senha em si nao trafega, so o novo salt/verifier SRP6.
 */
@Slf4j
@Service
public class PasswordRecoveryService {

    public static final String TYPE = "password.recover";

    /** Tentativas por usuario, certas ou erradas, para frear adivinhacao de e-mail. */
    private static final int MAX_PER_USER = 5;
    private static final long WINDOW_MS = 60 * 60_000L;

    private final AbraClient abra;
    private final WowlkRepository repo;
    private final AccountMailer mailer;
    private final WowlkProperties props;
    private final RateLimiter perUser;
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

    public PasswordRecoveryService(AbraClient abra, WowlkRepository repo, AccountMailer mailer, WowlkProperties props) {
        this.abra = abra;
        this.repo = repo;
        this.mailer = mailer;
        this.props = props;
        this.perUser = new RateLimiter(MAX_PER_USER, WINDOW_MS, Clock.systemUTC());
    }

    /** Processa e apaga a mensagem, salvo erro temporario (fica para o proximo ciclo, ate 3 vezes). */
    public void process(QueueMessage m, JsonNode payload, String key) throws InterruptedException {
        JsonNode acc = payload.path("account");
        String username = NewAccount.normalizeUsername(acc.path("username").asText(""));
        String email = NewAccount.normalizeEmail(acc.path("email").asText(""));
        boolean pt = acc.path("locale").asText("").toLowerCase().startsWith("pt");

        NewAccount request;
        try {
            request = NewAccount.fromSrp6(username, email, acc.path("salt").asText(null), acc.path("verifier").asText(null), null, null);
        } catch (ApiException invalid) {
            log.warn("[wowlk] troca de senha RECUSADA (dados invalidos) usuario={}: {}", username, invalid.getMessage());
            delete(m, key);
            return;
        }
        // Conta so na primeira vez que a mensagem e vista: re-tentativa por erro nosso nao gasta o limite.
        if (!attempts.containsKey(m.id()) && perUser.hit(username) > 0) {
            log.warn("[wowlk] troca de senha RECUSADA (limite por usuario) usuario={}", username);
            delete(m, key);
            return;
        }

        try {
            Optional<AccountRow> found = repo.findByUsername(username);
            if (found.isEmpty() || !email.equalsIgnoreCase(found.get().email()) || repo.isPrivileged(found.get().id())) {
                log.warn("[wowlk] troca de senha RECUSADA usuario={} email={}: conta inexistente, e-mail diferente ou conta privilegiada", username, mask(email));
                attempts.remove(m.id());
                delete(m, key);
                return;
            }
            AccountRow account = found.get();
            repo.updatePassword(account.id(), request.salt(), request.verifier());
            repo.revokeAllSessions(account.id());
            attempts.remove(m.id());
            delete(m, key);
            log.info("[wowlk] senha ALTERADA: conta {} (id {})", account.username(), account.id());
            mailer.sendPasswordChanged(account.email(), account.username(), pt);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            int n = attempts.merge(m.id(), 1, Integer::sum);
            int max = Math.max(1, props.maxAttempts());
            if (n < max) {
                log.error("[wowlk] troca de senha FALHOU (tentativa {}/{}) usuario={}: {}", n, max, username, e.toString());
                return;
            }
            log.error("[wowlk] troca de senha FALHOU DEFINITIVAMENTE ({}/{}) usuario={}: {}", n, max, username, e.toString());
            attempts.remove(m.id());
            delete(m, key);
            mailer.sendPasswordFailed(email, username, pt);
        }
    }

    void prune() {
        perUser.prune();
    }

    private void delete(QueueMessage m, String key) throws InterruptedException {
        try {
            abra.delete(m.id(), key);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            // Reaplicar o mesmo salt/verifier no proximo ciclo nao muda nada.
            log.warn("[wowlk] nao consegui remover o evento {} da fila: {}", m.id(), e.getMessage());
        }
    }
}
