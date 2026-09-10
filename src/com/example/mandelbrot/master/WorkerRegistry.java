package com.example.mandelbrot.master;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Реестр воркеров + периодический health-check через HTTP /ping.
 *
 * Воркеры сами регистрируются (POST /register) и шлют heartbeat.
 * Дополнительно мастер пингует каждого раз в 10 секунд — если воркер
 * не отвечает, помечаем мёртвым.
 */
public final class WorkerRegistry {

    public static final class Entry {
        public final String workerId;
        public final String baseUrl;
        public volatile long lastSeenMs;
        public volatile boolean alive = true;

        public Entry(String workerId, String baseUrl, long lastSeenMs) {
            this.workerId = workerId;
            this.baseUrl = baseUrl;
            this.lastSeenMs = lastSeenMs;
        }

        @Override
        public String toString() {
            return workerId + " @ " + baseUrl
                    + (alive ? "" : " [DEAD]");
        }
    }

    private static final long PING_INTERVAL_MS = 10_000;
    private static final Duration PING_TIMEOUT = Duration.ofSeconds(3);

    private final Map<String, Entry> byId = new ConcurrentHashMap<>();
    private final AtomicBoolean monitorRunning = new AtomicBoolean(false);
    private Thread monitorThread;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    // ---------- Регистрация ----------

    public void register(String workerId, String baseUrl) {
        byId.compute(workerId, (k, old) -> {
            if (old == null) {
                System.out.println("[master] + воркер: " + workerId + " @ " + baseUrl);
            }
            return new Entry(workerId, baseUrl, System.currentTimeMillis());
        });
    }

    public void heartbeat(String workerId, String baseUrl) {
        register(workerId, baseUrl);
    }

    // ---------- Health monitor ----------

    public void startHealthMonitor() {
        if (!monitorRunning.getAndSet(true)) {
            monitorThread = new Thread(this::pingLoop, "worker-health");
            monitorThread.setDaemon(true);
            monitorThread.start();
            System.out.println("[master] health monitor запущен "
                    + "(ping каждые " + (PING_INTERVAL_MS / 1000) + " сек)");
        }
    }

    private void pingLoop() {
        while (monitorRunning.get()) {
            try {
                Thread.sleep(PING_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            pingAll();
        }
    }

    private void pingAll() {
        for (Entry e : byId.values()) {
            boolean ok = pingOne(e.baseUrl);
            boolean wasAlive = e.alive;
            e.alive = ok;
            if (ok) {
                e.lastSeenMs = System.currentTimeMillis();
            } else if (wasAlive) {
                System.out.println("[master] воркер не отвечает: "
                        + e.workerId + " @ " + e.baseUrl);
            }
        }
    }

    private boolean pingOne(String baseUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/ping"))
                    .timeout(PING_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200
                    && resp.body().contains("\"status\":\"ok\"");
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Список ----------

    /** Живые воркеры (прошли последний ping). */
    public List<Entry> alive() {
        List<Entry> result = new ArrayList<>();
        for (Entry e : byId.values()) {
            if (e.alive) result.add(e);
        }
        result.sort(Comparator.comparing(e -> e.workerId));
        return result;
    }

    /** Все воркеры, включая мёртвых — для отладки. */
    public List<Entry> all() {
        List<Entry> result = new ArrayList<>(byId.values());
        result.sort(Comparator.comparing(e -> e.workerId));
        return result;
    }

    public int size() {
        return byId.size();
    }
}