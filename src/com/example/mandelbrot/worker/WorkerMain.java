package com.example.mandelbrot.worker;

import com.example.mandelbrot.common.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Воркер.
 *
 * При старте:
 *   1) Определяет свой IP.
 *   2) Слушает UDP broadcast — ищет мастера (бесконечно, пока не найдёт).
 *   3) Поднимает HTTP-сервер для приёма /render и /ping.
 *   4) Регистрируется на мастере.
 *   5) Шлёт heartbeat каждые 3 секунды.
 *
 * --master убран. Только автоматический поиск.
 */
public final class WorkerMain {

    private static final long HEARTBEAT_INTERVAL_MS = 3_000;

    private final String masterUrl;
    private final String bindHost;
    private final int port;
    private final String workerId;
    private final String myPublicUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WorkerMain(String masterUrl, String bindHost, int port) {
        this.masterUrl = masterUrl;
        this.bindHost = bindHost;
        this.port = port;

        String ip = "0.0.0.0".equals(bindHost)
                ? NetUtils.detectLocalIp(URI.create(masterUrl).getHost())
                : bindHost;
        String shortIp = ip.substring(ip.lastIndexOf('.') + 1);   // последний октет
        this.workerId = "worker-" + shortIp + "-" + port;
        this.myPublicUrl = "http://" + ip + ":" + port;
    }

    public static void main(String[] args) throws Exception {
        String bindHost = "0.0.0.0";
        int port = 8081;
        boolean skipFirewall = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port"        -> port = Integer.parseInt(args[++i]);
                case "--bind"        -> bindHost = args[++i];
                case "--no-firewall" -> skipFirewall = true;
                default -> {
                    System.err.println("Неизвестный аргумент: " + args[i]);
                    System.err.println("Использование: [--port N] [--bind HOST] [--no-firewall]");
                    System.exit(2);
                }
            }
        }

        // Фаервол для порта воркера.
        if (!skipFirewall) {
            FirewallManager.ensureRule(
                    "Mandelbrot Worker HTTP " + port, port, "TCP", true);
            FirewallManager.ensureRule(
                    "Mandelbrot Worker Discovery", Discovery.DISCOVERY_PORT, "UDP", true);
        }

        // Ищем мастера бесконечно.
        System.out.println("[worker-" + port + "] стартую, ищу мастера...");
        String masterUrl = MasterDiscovery.discoverForever();

        // Дальше — как обычно.
        new WorkerMain(masterUrl, bindHost, port).start();
    }

    private void start() throws Exception {
        // HTTP-сервер воркера.
        HttpServer server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/ping",   this::handlePing);
        server.createContext("/render", this::handleRender);
        server.start();

        log("HTTP-сервер на " + bindHost + ":" + port);
        log("мой URL: " + myPublicUrl);
        log("мастер:  " + masterUrl);

        registerWithRetry();

        Thread hb = new Thread(this::heartbeatLoop, "heartbeat");
        hb.setDaemon(true);
        hb.start();

        log("готов, жду задачи от мастера");
    }

    // ============================================================
    //  Регистрация / heartbeat
    // ============================================================

    private void registerWithRetry() {
        for (int attempt = 1; attempt <= 30; attempt++) {
            if (tryRegister()) {
                log("зарегистрирован на мастере");
                return;
            }
            log("не удалось зарегистрироваться (попытка " + attempt + "/30), жду 2 с");
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        }
        log("НЕ УДАЛОСЬ зарегистрироваться за минуту, выходим");
        System.exit(1);
    }

    private boolean tryRegister() {
        try {
            String body = "{\"workerId\":\"" + workerId + "\","
                    + "\"baseUrl\":\"" + myPublicUrl + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(masterUrl + "/register"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void heartbeatLoop() {
        while (true) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
                String body = "{\"workerId\":\"" + workerId + "\","
                        + "\"baseUrl\":\"" + myPublicUrl + "\"}";
                HttpRequest req = HttpRequest.newBuilder(URI.create(masterUrl + "/heartbeat"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                log("heartbeat: " + e.getMessage());
                if (tryRegister()) {
                    log("перерегистрация: ok");
                }
            }
        }
    }

    // ============================================================
    //  HTTP-эндпоинты воркера
    // ============================================================

    private void handlePing(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        send(ex, 200, "{\"status\":\"ok\",\"worker_id\":\"" + workerId + "\"}");
    }

    private void handleRender(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        try {
            String reqBody = readBody(ex.getRequestBody());
            RenderTask task = RenderTask.fromJson(reqBody);

            long t0 = System.currentTimeMillis();
            int[] pixels = MandelbrotRenderer.renderStrip(task, task.antiAliasing); // ← AA
            long ms = System.currentTimeMillis() - t0;

            byte[] bytes = new byte[pixels.length * 4];
            for (int i = 0, j = 0; i < pixels.length; i++) {
                int p = pixels[i];
                bytes[j++] = (byte) (p >>> 24);
                bytes[j++] = (byte) (p >>> 16);
                bytes[j++] = (byte) (p >>> 8);
                bytes[j++] = (byte) p;
            }
            String b64 = Base64.getEncoder().encodeToString(bytes);

            RenderResult result = new RenderResult(
                    task.taskId, task.yStart, task.yEnd,
                    task.width, ms, b64);

            log("rendered rows " + task.yStart + ".." + task.yEnd
                    + " in " + ms + " ms");
            send(ex, 200, result.toJson());
        } catch (Exception e) {
            log("ОШИБКА при /render: " + e.getMessage());
            send(ex, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    // ============================================================
    //  Утилиты
    // ============================================================

    private static String readBody(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void log(String msg) {
        System.out.println("[" + workerId + "] " + msg);
    }
}