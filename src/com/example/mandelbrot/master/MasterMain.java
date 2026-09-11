package com.example.mandelbrot.master;

import com.example.mandelbrot.common.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public final class MasterMain {

    // ---------- Конфигурация рендера перенесена в отдельный модуль ----------

    // ---------- Сеть ----------
    static final int MASTER_PORT = 9000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final WorkerRegistry REGISTRY = new WorkerRegistry();

    public static void main(String[] args) throws Exception {
        String announceIp = null;
        boolean skipFirewall = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--announce-ip" -> announceIp = args[++i];
                case "--no-firewall" -> skipFirewall = true;
            }
        }

        // ---------- 2. Firewall ----------
        if (!skipFirewall) {
            FirewallManager.ensureRule(
                    "Mandelbrot Master HTTP", MASTER_PORT, "TCP", true);
            FirewallManager.ensureRule(
                    "Mandelbrot Master Discovery", Discovery.DISCOVERY_PORT, "UDP", true);
        }

        // ---------- 3. Конфигурация рендера ----------
        Scanner scanner = new Scanner(System.in);
        RenderConfig config = RenderConfig.fromArgsOrNull(args);
        if (config == null) {
            config = RenderConfig.interactive(scanner);
            if (config == null) {
                log("настройка отменена, выход");
                return;
            }
        } else {
            log("конфигурация из CLI: " + config.describe());
            String err = config.validate();
            if (err != null) {
                log("ОШИБКА в CLI: " + err);
                System.exit(2);
            }
        }

        // ---------- 4. HTTP-сервер мастера ----------
        log("стартую HTTP-сервер мастера на порту " + MASTER_PORT);
        startHttpServer(MASTER_PORT);

        // ---------- 5. Announcer ----------
        MasterAnnouncer announcer = new MasterAnnouncer(MASTER_PORT, announceIp);
        announcer.start();

        // ---------- 6. Health monitor ----------
        REGISTRY.startHealthMonitor();

        writeMyIpFile(announceIp);

        log("параметры рендера: " + config.describe());

        // ---------- 7. Основной цикл ----------
        while (true) {
            log("");
            log("=== Нажмите Enter, чтобы начать рендер ===");
            log("=== 'c' + Enter — изменить настройки ===");
            log("=== 'q' + Enter — выход ===");
            String line = scanner.nextLine().trim().toLowerCase();

            if ("q".equals(line)) break;
            if ("c".equals(line)) {
                RenderConfig updated = RenderConfig.interactive(scanner);
                if (updated != null) {
                    config = updated;
                    log("новые параметры: " + config.describe());
                }
                continue;
            }

            List<WorkerRegistry.Entry> alive = REGISTRY.alive();
            if (alive.isEmpty()) {
                log("Нет живых воркеров — жду подключения...");
                continue;
            }
            log("живых воркеров: " + alive.size());
            for (WorkerRegistry.Entry e : alive) {
                log("  " + e.workerId + "  " + e.baseUrl);
            }

            try {
                renderAndSave(alive, config);
            } catch (Exception e) {
                log("ошибка рендера: " + e.getMessage());
            }
        }
        log("выход");
    }

    // ============================================================
    //  HTTP-сервер мастера (те же эндпоинты, что были)
    // ============================================================

    private static void startHttpServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/register",  MasterMain::handleRegister);
        server.createContext("/heartbeat", MasterMain::handleHeartbeat);
        server.createContext("/workers",   MasterMain::handleWorkers);
        server.start();
    }

    private static void handleRegister(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        try {
            String body = readBody(ex.getRequestBody());
            Json j = Json.parse(body);
            String workerId = j.getString("workerId");
            String baseUrl  = j.getString("baseUrl");
            REGISTRY.register(workerId, baseUrl);
            send(ex, 200, "{\"status\":\"registered\"}");
        } catch (Exception e) {
            log("ошибка /register: " + e.getMessage());
            send(ex, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private static void handleHeartbeat(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        try {
            String body = readBody(ex.getRequestBody());
            Json j = Json.parse(body);
            REGISTRY.heartbeat(j.getString("workerId"), j.getString("baseUrl"));
            send(ex, 200, "{\"status\":\"ok\"}");
        } catch (Exception e) {
            send(ex, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private static void handleWorkers(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"workers\":[");
        List<WorkerRegistry.Entry> all = REGISTRY.all();
        long now = System.currentTimeMillis();
        for (int i = 0; i < all.size(); i++) {
            WorkerRegistry.Entry e = all.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"workerId\":\"").append(e.workerId).append("\",");
            sb.append("\"baseUrl\":\"").append(e.baseUrl).append("\",");
            sb.append("\"alive\":").append(e.alive).append(',');
            sb.append("\"ageMs\":").append(now - e.lastSeenMs).append('}');
        }
        sb.append("]}");
        send(ex, 200, sb.toString());
    }

    // ============================================================
    //  Рендер — та же логика, что была
    // ============================================================

    private static void renderAndSave(List<WorkerRegistry.Entry> alive,
                                      RenderConfig config) throws Exception {
        long t0 = System.currentTimeMillis();

        List<RenderTask> tasks = buildTasks(config);
        log("всего задач (полос): " + tasks.size());

        BufferedImage image = new BufferedImage(
                config.width, config.height, BufferedImage.TYPE_INT_RGB);

        ExecutorService pool = Executors.newFixedThreadPool(alive.size());
        AtomicInteger nextTask = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);
        final int totalTasks = tasks.size();

        List<Future<?>> futures = new ArrayList<>();
        for (WorkerRegistry.Entry worker : alive) {
            futures.add(pool.submit(() ->
                    workerLoop(worker.baseUrl, tasks, nextTask,
                            completed, totalTasks, image)));
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) {
                log("поток воркера упал: " + e.getMessage());
            }
        }
        pool.shutdown();

        long elapsed = System.currentTimeMillis() - t0;
        log("все задачи завершены за " + elapsed + " ms");

        File out = new File("mandelbrot.png");
        ImageIO.write(image, "png", out);
        log("сохранил " + out.getAbsolutePath()
                + " (" + out.length() / 1024 + " КБ)");
    }

    private static List<RenderTask> buildTasks(RenderConfig config) {
        List<RenderTask> tasks = new ArrayList<>();
        int id = 0;
        for (int y = 0; y < config.height; y += config.stripHeight) {
            int yEnd = Math.min(y + config.stripHeight - 1, config.height - 1);
            tasks.add(new RenderTask(id++, y, yEnd,
                    config.width, config.height, config.maxIter,
                    config.xMin, config.xMax, config.yMin, config.yMax,
                    config.antiAliasing));
        }
        return tasks;
    }

    private static void workerLoop(String workerBaseUrl,
                                   List<RenderTask> tasks,
                                   AtomicInteger nextTask,
                                   AtomicInteger completed,
                                   int totalTasks,
                                   BufferedImage image) {
        while (true) {
            int idx = nextTask.getAndIncrement();
            if (idx >= tasks.size()) return;
            RenderTask task = tasks.get(idx);

            boolean ok = false;
            int attempts = 0;
            while (!ok && attempts < 2) {
                attempts++;
                try {
                    RenderResult res = callRender(workerBaseUrl, task);
                    applyResult(image, res);
                    int done = completed.incrementAndGet();
                    log("task " + task.taskId
                            + " rows " + task.yStart + ".." + task.yEnd
                            + " done (" + done + "/" + totalTasks
                            + ", " + res.renderMs + " ms)"
                            + (attempts > 1 ? " [retry]" : ""));
                    ok = true;
                } catch (Exception e) {
                    log("task " + task.taskId
                            + " ошибка на " + workerBaseUrl
                            + " (попытка " + attempts + "): " + e.getMessage());
                }
            }
            if (!ok) {
                log("task " + task.taskId + " НЕ ВЫПОЛНЕНА после retry");
            }
        }
    }

    private static RenderResult callRender(String baseUrl, RenderTask task) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/render"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(task.toJson()))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return RenderResult.fromJson(resp.body());
    }

    private static void applyResult(BufferedImage image, RenderResult res) {
        byte[] raw = Base64.getDecoder().decode(res.pixelsBase64);
        int rows = res.yEnd - res.yStart + 1;
        int width = res.width;
        if (raw.length != width * rows * 4) {
            throw new IllegalStateException("Неожиданная длина base64: " + raw.length);
        }
        int[] pixels = new int[width * rows];
        for (int i = 0, j = 0; i < pixels.length; i++) {
            int p = ((raw[j++] & 0xFF) << 24)
                    | ((raw[j++] & 0xFF) << 16)
                    | ((raw[j++] & 0xFF) << 8)
                    |  (raw[j++] & 0xFF);
            pixels[i] = p;
        }
        synchronized (image) {
            image.setRGB(0, res.yStart, width, rows, pixels, 0, width);
        }
    }

    // ============================================================
    //  IP-файл
    // ============================================================

    /**
     * Пишет в master-ip.txt URL мастера.
     * Если announceIp задан явно — использует его.
     * Иначе — определяет через NetUtils.
     */
    private static void writeMyIpFile(String explicitIp) {
        try {
            String ip;
            if (explicitIp != null && !explicitIp.isBlank()) {
                ip = explicitIp;
            } else {
                ip = NetUtils.detectLocalIp("8.8.8.8");
            }
            String url = "http://" + ip + ":" + MASTER_PORT;

            Path file = Path.of(System.getProperty("user.home"), "master-ip.txt");
            Files.writeString(file, url + System.lineSeparator());

            log("мой IP для воркеров: " + url);
            log("записал в " + file.toAbsolutePath());
        } catch (Exception e) {
            log("не удалось записать master-ip.txt: " + e.getMessage());
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

    private static void log(String msg) {
        System.out.println("[master] " + msg);
    }
}