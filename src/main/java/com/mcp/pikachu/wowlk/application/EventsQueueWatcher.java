package com.mcp.pikachu.wowlk.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.pikachu.wowlk.config.WowlkProperties;
import com.mcp.pikachu.wowlk.config.WowlkSecrets;
import com.mcp.pikachu.wowlk.infra.AbraClient;
import com.mcp.pikachu.wowlk.infra.WowlkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/**
 * Fila de eventos do site (WOWLK_QUEUE_EVENTS_KEY): account.verify, password.reset, downloads.subscribe...
 * Nesta primeira fase so cadastro e login existem; os eventos sao apenas contados no log e
 * NAO sao apagados, para nao perder nada ate os handlers existirem. Tambem faz a limpeza das sessoes vencidas.
 */
@Slf4j
@Component
public class EventsQueueWatcher {

    private final AbraClient abra;
    private final WowlkRepository repo;
    private final WowlkProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String lastSummary = "";

    public EventsQueueWatcher(AbraClient abra, WowlkRepository repo, WowlkProperties props) {
        this.abra = abra;
        this.repo = repo;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${wowlk.poll-interval-ms:120000}", initialDelayString = "${wowlk.poll-initial-delay-ms:20000}")
    public void watch() {
        String key = WowlkSecrets.eventsKey();
        if (!props.enabled() || key == null) return;
        try {
            Map<String, Integer> byType = new TreeMap<>();
            for (AbraClient.QueueMessage m : abra.retrieve(key)) {
                String type = m.title();
                try {
                    var n = mapper.readTree(m.content());
                    if (n.hasNonNull("type")) type = n.get("type").asText();
                } catch (Exception ignored) {
                    // conteudo cifrado ou invalido: usa o titulo
                }
                byType.merge(type == null ? "?" : type, 1, Integer::sum);
            }
            String summary = byType.toString();
            if (!summary.equals(lastSummary)) {
                if (!byType.isEmpty()) log.info("[wowlk] fila de eventos pendente (ainda sem processamento): {}", summary);
                lastSummary = summary;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("[wowlk] fila de eventos nao consultada: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void purgeSessions() {
        try {
            int n = repo.purgeExpiredSessions();
            if (n > 0) log.info("[wowlk] {} sessao(oes) vencida(s) removida(s)", n);
        } catch (Exception e) {
            log.debug("[wowlk] limpeza de sessoes falhou: {}", e.getMessage());
        }
    }
}
