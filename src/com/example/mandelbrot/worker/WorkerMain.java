package com.example.mandelbrot.worker;

import com.example.mandelbrot.common.MandelbrotRenderer;
import com.example.mandelbrot.common.RenderResult;
import com.example.mandelbrot.common.RenderTask;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;

/**
 * Воркер: HTTP-сервер на встроенном com.sun.net.httpserver.HttpServer.
 * Эндпоинты:
 *   GET  /ping   -> {"status":"ok","worker_id":"worker-8081"}
 *   POST /render -> принимает RenderTask (JSON), возвращает RenderResult (JSON).
 *
 * Воркер идемпотентен: любая полоса рендерится независимо.
 */
public final class WorkerMain {

    private final String workerId;

    private WorkerMain(int port) {
        this.workerId = "worker-" + port;
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        new WorkerMain(port).start();
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port()), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/ping",   this::handlePing);
        server.createContext("/render", this::handleRender);
        server.start();
        log("стартовал на порту " + port());
    }

    private int port() {
        return Integer.parseInt(workerId.substring("worker-".length()));
    }

    // ---------- /ping ----------

    private void handlePing(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        String body = "{\"status\":\"ok\",\"worker_id\":\"" + workerId + "\"}";
        send(ex, 200, body);
    }

    // ---------- /render ----------

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

            // Упаковываем int[] -> byte[] (4 байта на пиксель) -> Base64.
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

    // ---------- Утилиты ----------

    private static String readBody(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type",
                "application/json; charset=utf-8");
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