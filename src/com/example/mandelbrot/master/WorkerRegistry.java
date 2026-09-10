package com.example.mandelbrot.master;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр воркеров, которые сами подключились к мастеру.
 *
 * Потокобезопасен: heartbeat и регистрация приходят по HTTP из разных потоков,
 * а мастер читает список живых воркеров из своих рабочих потоков.
 */
public final class WorkerRegistry {

    /** Запись о воркере. */
    public static final class Entry {
        public final String workerId;    // "worker-8081" или "worker-192.168.1.42:8081"
        public final String baseUrl;     // "http://192.168.1.42:8081"
        public volatile long lastSeenMs; // время последнего heartbeat/регистрации

        public Entry(String workerId, String baseUrl, long lastSeenMs) {
            this.workerId = workerId;
            this.baseUrl = baseUrl;
            this.lastSeenMs = lastSeenMs;
        }

        @Override
        public String toString() {
            return workerId + " @ " + baseUrl + " (lastSeen " + lastSeenMs + ")";
        }
    }

    private final Map<String, Entry> byId = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public WorkerRegistry(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /** Регистрация или обновление воркера. */
    public void register(String workerId, String baseUrl) {
        byId.compute(workerId, (k, old) ->
                new Entry(workerId, baseUrl, System.currentTimeMillis()));
    }

    /** Heartbeat — обновляем lastSeen. Если воркер не был зарегистрирован — регистрируем. */
    public void heartbeat(String workerId, String baseUrl) {
        register(workerId, baseUrl);
    }

    /** Убирает воркера, который давно не слал heartbeat. */
    public List<Entry> alive() {
        long now = System.currentTimeMillis();
        List<Entry> result = new ArrayList<>();
        for (Entry e : byId.values()) {
            if (now - e.lastSeenMs <= timeoutMs) {
                result.add(e);
            }
        }
        result.sort(Comparator.comparing(e -> e.workerId));
        return result;
    }

    /** Список всех (включая мёртвых) — для отладки. */
    public List<Entry> all() {
        List<Entry> result = new ArrayList<>(byId.values());
        result.sort(Comparator.comparing(e -> e.workerId));
        return result;
    }

    /** Удаляет мёртвых из реестра. */
    public void prune() {
        long now = System.currentTimeMillis();
        byId.entrySet().removeIf(en -> now - en.getValue().lastSeenMs > timeoutMs * 3);
    }

    public int size() {
        return byId.size();
    }
}