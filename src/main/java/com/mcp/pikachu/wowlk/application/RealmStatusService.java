package com.mcp.pikachu.wowlk.application;

import com.mcp.pikachu.wowlk.config.WowlkProperties;
import com.mcp.pikachu.wowlk.infra.WowlkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Painel do site: reino no ar, jogadores online e total de contas, somando os bots residentes.
 * Rota publica chamada por cada visitante, por isso o resultado fica 30 s em cache.
 */
@Slf4j
@Service
public class RealmStatusService {

    public record RealmStatus(boolean online, Integer playersOnline, Integer totalAccounts) {
    }

    private static final long CACHE_MS = 30_000;
    private static final int PROBE_MS = 2_000;

    private final WowlkRepository repo;
    private final WowlkProperties props;
    private RealmStatus cached;
    private long cachedAt;

    public RealmStatusService(WowlkRepository repo, WowlkProperties props) {
        this.repo = repo;
        this.props = props;
    }

    public synchronized RealmStatus current() {
        long now = System.currentTimeMillis();
        if (cached == null || now - cachedAt >= CACHE_MS) {
            cached = read();
            cachedAt = now;
        }
        return cached;
    }

    private RealmStatus read() {
        boolean online = worldAnswers();
        try {
            int bots = props.residentBots();
            return new RealmStatus(online, online ? bots + repo.countHumansOnline() : 0, bots + repo.countHumanAccounts());
        } catch (RuntimeException e) {
            log.warn("[wowlk] painel do reino sem numeros: {}", e.getMessage());
            return new RealmStatus(online, null, null);
        }
    }

    /** O proxy do Docker aceita conexao mesmo com o worldserver parado; so vale se o servidor mandar o desafio. */
    private boolean worldAnswers() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", props.worldPort()), PROBE_MS);
            socket.setSoTimeout(PROBE_MS);
            return socket.getInputStream().read() >= 0;
        } catch (IOException e) {
            return false;
        }
    }
}
