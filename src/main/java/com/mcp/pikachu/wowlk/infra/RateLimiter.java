package com.mcp.pikachu.wowlk.infra;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitador em memoria por janela fixa (uma instancia do Pikachu, sem Redis).
 * {@link #hit} conta e retorna 0 se liberado, ou os segundos ate liberar.
 */
public class RateLimiter {

    private record Window(long startMs, int count) {
    }

    private final int limit;
    private final long windowMs;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int limit, long windowMs, Clock clock) {
        this.limit = limit;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /** Conta uma ocorrencia. Retorna 0 se ainda dentro do limite, senao Retry-After em segundos. */
    public long hit(String key) {
        long now = clock.millis();
        Window w = windows.compute(key, (k, cur) ->
                cur == null || now - cur.startMs >= windowMs ? new Window(now, 1) : new Window(cur.startMs, cur.count + 1));
        return w.count <= limit ? 0 : retryAfter(w, now);
    }

    /** Ja estourou? (sem contar). */
    public long blockedFor(String key) {
        long now = clock.millis();
        Window w = windows.get(key);
        if (w == null || now - w.startMs >= windowMs || w.count < limit) return 0;
        return retryAfter(w, now);
    }

    public void reset(String key) {
        windows.remove(key);
    }

    /** Limpa janelas vencidas (chamado periodicamente para nao crescer sem limite). */
    public void prune() {
        long now = clock.millis();
        windows.entrySet().removeIf(e -> now - e.getValue().startMs >= windowMs);
    }

    private long retryAfter(Window w, long now) {
        return Math.max(1, (w.startMs + windowMs - now + 999) / 1000);
    }
}
