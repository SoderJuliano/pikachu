package com.mcp.pikachu.wowlk.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.pikachu.wowlk.config.WowlkProperties;
import com.mcp.pikachu.wowlk.config.WowlkSecrets;
import com.mcp.pikachu.wowlk.infra.AbraClient;
import com.mcp.pikachu.wowlk.infra.QueuePayloadDecryptor;
import com.mcp.pikachu.wowlk.infra.WowlkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/**
 * Fila de eventos do site (WOWLK_QUEUE_EVENTS_KEY). Processa {@code password.recover}; os demais
 * tipos (ex.: downloads.subscribe) so sao contados no log e NAO sao apagados, para nao perder nada
 * ate existir quem os trate. Tambem faz a limpeza das sessoes vencidas.
 */
@Slf4j
@Component
public class EventsQueueWatcher {

    private final AbraClient abra;
    private final WowlkRepository repo;
    private final PasswordRecoveryService passwords;
    private final QueuePayloadDecryptor decryptor;
    private final WowlkProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String lastSummary = "";

    public EventsQueueWatcher(AbraClient abra, WowlkRepository repo, PasswordRecoveryService passwords,
                              QueuePayloadDecryptor decryptor, WowlkProperties props) {
        this.abra = abra;
        this.repo = repo;
        this.passwords = passwords;
        this.decryptor = decryptor;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${wowlk.poll-interval-ms:120000}", initialDelayString = "${wowlk.poll-initial-delay-ms:20000}")
    public void watch() {
        String key = WowlkSecrets.eventsKey();
        if (!props.enabled() || key == null) return;
        try {
            runOnce(key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[wowlk] fila de eventos nao consultada: {}", e.getMessage());
        }
    }

    void runOnce(String key) throws Exception {
        Map<String, Integer> pending = new TreeMap<>();
        int handled = 0;
        int limit = Math.max(1, props.maxRegistrationsPerRun());
        for (AbraClient.QueueMessage m : abra.retrieve(key)) {
            JsonNode payload = parse(m);
            String type = payload != null && payload.hasNonNull("type") ? payload.get("type").asText() : m.title();
            if (PasswordRecoveryService.TYPE.equals(type) && payload != null) {
                if (handled++ < limit) passwords.process(m, payload, key);
            } else {
                pending.merge(type == null ? "?" : type, 1, Integer::sum);
            }
        }
        String summary = pending.toString();
        if (!summary.equals(lastSummary)) {
            if (!pending.isEmpty()) log.info("[wowlk] eventos sem processamento na fila: {}", summary);
            lastSummary = summary;
        }
    }

    private JsonNode parse(AbraClient.QueueMessage m) {
        try {
            JsonNode node = mapper.readTree(m.content());
            if (decryptor.isEncrypted(node)) node = mapper.readTree(decryptor.open(node));
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void purgeSessions() {
        passwords.prune();
        try {
            int n = repo.purgeExpiredSessions();
            if (n > 0) log.info("[wowlk] {} sessao(oes) vencida(s) removida(s)", n);
        } catch (Exception e) {
            log.debug("[wowlk] limpeza de sessoes falhou: {}", e.getMessage());
        }
    }
}
