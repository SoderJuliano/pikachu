package com.mcp.pikachu.wowlk.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.pikachu.wowlk.config.WowlkProperties;
import com.mcp.pikachu.wowlk.config.WowlkSecrets;
import com.mcp.pikachu.wowlk.domain.ApiException;
import com.mcp.pikachu.wowlk.domain.NewAccount;
import com.mcp.pikachu.wowlk.infra.AbraClient;
import com.mcp.pikachu.wowlk.infra.AbraClient.QueueMessage;
import com.mcp.pikachu.wowlk.infra.QueuePayloadDecryptor;
import com.mcp.pikachu.wowlk.infra.WowlkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.mcp.pikachu.wowlk.application.AccountMailer.mask;

/**
 * Consome a fila de cadastro do site (Abra API, chave WOWLK_QUEUE_REGISTRATION_KEY) a cada 2 min.
 *
 * <ul>
 *   <li><b>Fail fast:</b> fila vazia (404) sai na hora; Abra fora do ar sai sem tocar em nada;
 *       banco fora do ar nao tenta mensagem por mensagem — conta uma tentativa para todas e sai.</li>
 *   <li><b>Concorrencia:</b> {@code fixedDelay} = um ciclo por vez (o proximo so comeca 2 min depois
 *       do anterior terminar). Cada mensagem e apagada pelo seu proprio id, so depois de processada —
 *       mensagens que chegam durante o ciclo ficam intactas para o proximo. O resultado fica gravado
 *       por {@code payload.id} em wowlk_site.registration_event: se o delete falhar, o proximo ciclo
 *       ve que ja foi processada e so apaga (sem conta nem e-mail duplicados).</li>
 *   <li><b>Retry:</b> erro transitorio mantem a mensagem na fila; na 3a falha manda o e-mail de
 *       desculpas e tira da fila. Erros permanentes (payload invalido, nome/e-mail em uso) nao
 *       sao re-tentados.</li>
 * </ul>
 */
@Slf4j
@Component
public class RegistrationQueueWorker {

    static final String TYPE = "account.register";

    private final AbraClient abra;
    private final WowlkRepository repo;
    private final AccountRegistrationService registration;
    private final AccountMailer mailer;
    private final QueuePayloadDecryptor decryptor;
    private final WowlkProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Tentativas por id de notificacao (memoria; reiniciar o Pikachu so concede tentativas extras). */
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
    private volatile boolean warnedMissingKey;

    public RegistrationQueueWorker(AbraClient abra, WowlkRepository repo, AccountRegistrationService registration,
                                   AccountMailer mailer, QueuePayloadDecryptor decryptor, WowlkProperties props) {
        this.abra = abra;
        this.repo = repo;
        this.registration = registration;
        this.mailer = mailer;
        this.decryptor = decryptor;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${wowlk.poll-interval-ms:120000}", initialDelayString = "${wowlk.poll-initial-delay-ms:20000}")
    public void poll() {
        if (!props.enabled()) return;
        String key = WowlkSecrets.registrationKey();
        if (key == null) {
            if (!warnedMissingKey) log.warn("[wowlk] WOWLK_QUEUE_REGISTRATION_KEY ausente no secrets.txt — fila de cadastro desligada.");
            warnedMissingKey = true;
            return;
        }
        try {
            runOnce(key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[wowlk] ciclo da fila de cadastro abortado: {}", e.toString());
        }
    }

    void runOnce(String key) throws Exception {
        List<QueueMessage> messages;
        try {
            messages = abra.retrieve(key);
        } catch (Exception e) {
            log.warn("[wowlk] Abra API indisponivel, fila nao consultada: {}", e.getMessage());
            return;
        }
        Set<String> present = messages.stream().map(QueueMessage::id).collect(Collectors.toSet());
        attempts.keySet().retainAll(present);
        if (messages.isEmpty()) return;

        log.info("[wowlk] {} cadastro(s) na fila", messages.size());
        try {
            repo.ping();
        } catch (Exception e) {
            log.error("[wowlk] banco do AzerothCore indisponivel ({}). Nenhum cadastro tentado neste ciclo.", e.getMessage());
            for (QueueMessage m : messages) transientFailure(m, key, parseQuietly(m), "database unavailable: " + e.getMessage());
            return;
        }

        int limit = Math.max(1, props.maxRegistrationsPerRun());
        if (messages.size() > limit)
            log.warn("[wowlk] {} mensagens na fila, processando {} neste ciclo (limite por ciclo)", messages.size(), limit);
        for (QueueMessage m : messages.subList(0, Math.min(limit, messages.size()))) {
            process(m, key);
        }
    }

    private void process(QueueMessage m, String key) throws InterruptedException {
        JsonNode payload;
        try {
            payload = parse(m);
        } catch (Exception e) {
            log.error("[wowlk] cadastro DESCARTADO (mensagem {} ilegivel): {}", m.id(), e.getMessage());
            safeSave(m.id(), m, null, null, "INVALID", 1, "unreadable: " + e.getMessage(), null);
            delete(m, key);
            return;
        }
        String payloadId = text(payload, "id", m.id());
        JsonNode acc = payload.path("account");
        String username = NewAccount.normalizeUsername(text(acc, "username", null));
        String email = NewAccount.normalizeEmail(text(acc, "email", null));
        boolean pt = text(acc, "locale", "").toLowerCase().startsWith("pt");

        try {
            if (!TYPE.equals(text(payload, "type", null))) {
                log.error("[wowlk] cadastro DESCARTADO: tipo '{}' na fila de cadastro (mensagem {})", text(payload, "type", null), m.id());
                repo.saveRegistrationEvent(payloadId, m.id(), username, email, "INVALID", 1, "wrong type", null);
                delete(m, key);
                return;
            }
            String done = repo.registrationStatus(payloadId).orElse(null);
            if (done != null && !"RETRY".equals(done)) {
                log.info("[wowlk] mensagem {} ja processada ({}) — so removendo da fila", m.id(), done);
                delete(m, key);
                return;
            }

            NewAccount account;
            try {
                account = NewAccount.fromSrp6(acc.path("username").asText(null), acc.path("email").asText(null),
                        acc.path("salt").asText(null), acc.path("verifier").asText(null),
                        acc.hasNonNull("expansion") ? acc.path("expansion").asInt() : null, text(acc, "locale", null));
            } catch (ApiException invalid) {
                log.error("[wowlk] cadastro FALHOU (dados invalidos) usuario={} email={}: {}", username, mask(email), invalid.getMessage());
                repo.saveRegistrationEvent(payloadId, m.id(), username, email, "INVALID", 1, invalid.getMessage(), null);
                delete(m, key);
                if (NewAccount.isValidEmail(email)) mailer.sendFailed(email, username, pt);
                return;
            }

            AccountRegistrationService.Result r = registration.register(account);
            switch (r.outcome()) {
                case CREATED, ALREADY_CREATED -> {
                    repo.saveRegistrationEvent(payloadId, m.id(), username, email, "CREATED", attempts.getOrDefault(m.id(), 0) + 1, null, r.accountId());
                    attempts.remove(m.id());
                    delete(m, key);
                    log.info("[wowlk] cadastro OK: conta {} (id {}) criada e ativa, email={}", username, r.accountId(), mask(email));
                    if (r.outcome() == AccountRegistrationService.Outcome.CREATED) mailer.sendActivated(email, username, account.prefersPortuguese());
                }
                case CONFLICT -> {
                    repo.saveRegistrationEvent(payloadId, m.id(), username, email, "CONFLICT", 1, "username or e-mail in use", null);
                    delete(m, key);
                    log.warn("[wowlk] cadastro RECUSADO: usuario {} ou email {} ja em uso", username, mask(email));
                    mailer.sendConflict(email, username, account.prefersPortuguese());
                }
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            transientFailure(m, key, payload, e.toString());
        }
    }

    /** Erro transitorio: mantem na fila ate a 3a tentativa; ai pede desculpas por e-mail e remove. */
    private void transientFailure(QueueMessage m, String key, JsonNode payload, String error) throws InterruptedException {
        int n = attempts.merge(m.id(), 1, Integer::sum);
        JsonNode acc = payload == null ? null : payload.path("account");
        String username = acc == null ? null : NewAccount.normalizeUsername(text(acc, "username", null));
        String email = acc == null ? null : NewAccount.normalizeEmail(text(acc, "email", null));
        String payloadId = payload == null ? m.id() : text(payload, "id", m.id());
        int max = Math.max(1, props.maxAttempts());

        if (n < max) {
            log.error("[wowlk] cadastro FALHOU (tentativa {}/{}) usuario={} email={}: {} — nova tentativa no proximo ciclo",
                    n, max, username, mask(email), error);
            safeSave(payloadId, m, username, email, "RETRY", n, error, null);
            return;
        }
        log.error("[wowlk] cadastro FALHOU DEFINITIVAMENTE ({}/{} tentativas) usuario={} email={}: {} — avisando o jogador",
                n, max, username, mask(email), error);
        safeSave(payloadId, m, username, email, "FAILED", n, error, null);
        boolean pt = acc != null && text(acc, "locale", "").toLowerCase().startsWith("pt");
        if (NewAccount.isValidEmail(email)) mailer.sendFailed(email, username, pt);
        delete(m, key);
        attempts.remove(m.id());
    }

    private void delete(QueueMessage m, String key) throws InterruptedException {
        try {
            abra.delete(m.id(), key);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            // O resultado ja esta gravado; o proximo ciclo so remove a mensagem.
            log.warn("[wowlk] nao consegui remover a mensagem {} da fila: {}", m.id(), e.getMessage());
        }
    }

    private void safeSave(String payloadId, QueueMessage m, String username, String email, String status, int n, String error, Long accountId) {
        try {
            repo.saveRegistrationEvent(payloadId, m.id(), username, email, status, n, error, accountId);
        } catch (Exception ignored) {
            // banco fora do ar: o log acima ja registra a falha
        }
    }

    private JsonNode parse(QueueMessage m) throws Exception {
        if (m.content() == null || m.content().isBlank()) throw new IllegalArgumentException("empty content");
        JsonNode node = mapper.readTree(m.content());
        if (decryptor.isEncrypted(node)) node = mapper.readTree(decryptor.open(node));
        if (node == null || !node.isObject()) throw new IllegalArgumentException("content is not a JSON object");
        return node;
    }

    private JsonNode parseQuietly(QueueMessage m) {
        try {
            return parse(m);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field, String fallback) {
        if (n == null) return fallback;
        JsonNode v = n.get(field);
        return v == null || v.isNull() || !v.isValueNode() ? fallback : v.asText();
    }
}
