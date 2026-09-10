package com.example.mandelbrot.worker;

import com.example.mandelbrot.common.Json;
import com.example.mandelbrot.common.MandelbrotRenderer;
import com.example.mandelbrot.common.NetUtils;
import com.example.mandelbrot.common.RenderResult;
import com.example.mandelbrot.common.RenderTask;
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
 *   1) Поднимает HTTP-сервер на своём порту (для приёма /render и /ping).
 *   2) Регистрируется на мастере (POST /register), сообщая URL,
 *      по которому мастер сможет к нему обратиться.
 *   3) В фоне раз в 3 секунды шлёт heartbeat (POST /heartbeat).
 *
 * CLI:
 *   java ... WorkerMain --master http://192.168.1.10:9000
 *   java ... WorkerMain --master http://192.168.1.10:9000 --port 8081
 *   java ... WorkerMain --master http://192.168.1.10:9000 --port 8081 --bind 0.0.0.0
 */
public final class WorkerMain {

    private static final long HEARTBEAT_INTERVAL_MS = 3_000;

    private final String masterUrl;
    private final String bindHost;
    private final int port;
    private final String workerId;
    private final String myPublicUrl;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WorkerMain(String masterUrl, String bindHost, int port) {
        this.masterUrl = masterUrl;
        this.bindHost = bindHost;
        this.port = port;
        this.workerId = "worker-" + port;

        // Определяем IP, который виден наружу — по нему мастер до нас дотянется.
        // Если bind задан явно и это не 0.0.0.0 — используем его.
        String ip;
        if ("0.0.0.0".equals(bindHost)) {
            String masterHost = URI.create(masterUrl).getHost();
            ip = NetUtils.detectLocalIp(masterHost);
        } else {
            ip = bindHost;
        }
        this.myPublicUrl = "http://" + ip + ":" + port;
    }

    public static void main(String[] args) throws Exception {
        String masterUrl = null;
        String bindHost = "0.0.0.0";
        int port = 8081;

        // Простой разбор аргументов.
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--master" -> masterUrl = args[++i];
                case "--port"   -> port = Integer.parseInt(args[++i]);
                case "--bind"   -> bindHost = args[++i];
                default -> {
                    System.err.println("Неизвестный аргумент: " + args[i]);
                    System.err.println("Использование: --master URL [--port N] [--bind HOST]");
                    System.exit(2);
                }
            }
        }
        if (masterUrl == null) {
            System.err.println("Обязателен аргумент --master http://<ip>:<port>");
            System.exit(2);
        }

        new WorkerMain(masterUrl, bindHost, port).start();
    }

    private void start() throws Exception {
        // 1. HTTP-сервер воркера.
        HttpServer server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/ping",   this::handlePing);
        server.createContext("/render", this::handleRender);
        server.start();

        log("HTTP-сервер на " + bindHost + ":" + port);
        log("мой URL для мастера: " + myPublicUrl);
        log("мастер: " + masterUrl);

        // 2. Регистрация на мастере (с retry — вдруг мастер ещё поднимается).
        registerWithRetry();

        // 3. Heartbeat в фоне.
        Thread hb = new Thread(this::heartbeatLoop, "heartbeat");
        hb.setDaemon(true);
        hb.start();

        log("готов, жду задачи от мастера");
    }

    // ============================================================
    //  Регистрация и heartbeat
    // ============================================================

    private void registerWithRetry() {
        for (int attempt = 1; attempt <= 30; attempt++) {
            if (tryRegister()) {
                registered.set(true);
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
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    log("heartbeat: HTTP " + resp.statusCode());
                }
            } catch (Exception e) {
                log("heartbeat не прошёл: " + e.getMessage());
                // Пытаемся перерегистрироваться на случай, если мастер
                // перезапустился и забыл про нас.
                if (tryRegister()) {
                    log("перерегистрация после сбоя heartbeat: ok");
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
        String body = "{\"status\":\"ok\",\"worker_id\":\"" + workerId + "\"}";
        send(ex, 200, body);
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
            int[] pixels = MandelbrotRenderer.renderStrip(task);
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
                    task.taskId, task.yStart, task.yEnd, task.width, ms, b64);

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