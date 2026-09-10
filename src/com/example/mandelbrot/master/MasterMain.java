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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public final class MasterMain {

    // ---------- Конфигурация рендера ----------
    static int WIDTH        = 1600;
    static int HEIGHT       = 1200;
    static int MAX_ITER     = 500;
    static double X_MIN     = -2.5;
    static double X_MAX     =  1.0;
    static double Y_MIN     = -1.2;
    static double Y_MAX     =  1.2;
    static int STRIP_HEIGHT = 40;

    // ---------- Сеть ----------
    static final int MASTER_PORT = 9000;
    static final long WORKER_TIMEOUT_MS = 30_000; // для реестра (не используется в ping-модели)

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final WorkerRegistry REGISTRY = new WorkerRegistry();

    public static void main(String[] args) throws Exception {
        // Разбор аргументов (те же, что раньше).
        String announceIp = null;
        boolean skipFirewall = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--width"        -> WIDTH        = Integer.parseInt(args[++i]);
                case "--height"       -> HEIGHT       = Integer.parseInt(args[++i]);
                case "--max-iter"     -> MAX_ITER     = Integer.parseInt(args[++i]);
                case "--strip"        -> STRIP_HEIGHT = Integer.parseInt(args[++i]);
                case "--x-min"        -> X_MIN        = Double.parseDouble(args[++i]);
                case "--x-max"        -> X_MAX        = Double.parseDouble(args[++i]);
                case "--y-min"        -> Y_MIN        = Double.parseDouble(args[++i]);
                case "--y-max"        -> Y_MAX        = Double.parseDouble(args[++i]);
                case "--announce-ip"  -> announceIp   = args[++i];
                case "--no-firewall"  -> skipFirewall = true;
                default -> {
                    log("Неизвестный аргумент: " + a);
                    System.exit(2);
                }
            }
        }

        // Фаервол.
        if (!skipFirewall) {
            FirewallManager.Status s1 = FirewallManager.ensureRule(
                    "Mandelbrot Master HTTP", MASTER_PORT, "TCP", true);
            FirewallManager.Status s2 = FirewallManager.ensureRule(
                    "Mandelbrot Master Discovery", Discovery.DISCOVERY_PORT, "UDP", true);

            if (s1 == FirewallManager.Status.PERMISSION_DENIED
                    || s1 == FirewallManager.Status.FAILED
                    || s2 == FirewallManager.Status.PERMISSION_DENIED
                    || s2 == FirewallManager.Status.FAILED) {
                log("ВНИМАНИЕ: не все правила фаервола созданы.");
                log("Воркеры могут не найти мастера или не подключиться.");
            }
        }

        log("стартую HTTP-сервер мастера на порту " + MASTER_PORT);
        startHttpServer(MASTER_PORT);

        // Announcer — рассылает broadcast.
        MasterAnnouncer announcer = new MasterAnnouncer(MASTER_PORT, announceIp);
        announcer.start();

        // Health monitor — пингует воркеров каждые 10 сек.
        REGISTRY.startHealthMonitor();

        writeMyIpFile();

        log("параметры рендера: " + WIDTH + "x" + HEIGHT
                + ", maxIter=" + MAX_ITER
                + ", strip=" + STRIP_HEIGHT);

        // Основной цикл: ждём Enter → рендерим → снова ждём.
        BufferedReader console = new BufferedReader(
                new InputStreamReader(System.in));

        while (true) {
            log("");
            log("=== Нажмите Enter, чтобы начать рендер ===");
            log("=== (Ctrl+C — выход) ===");
            String line = console.readLine();
            if (line == null) break; // EOF

            List<WorkerRegistry.Entry> alive = REGISTRY.alive();
            if (alive.isEmpty()) {
                log("Нет живых воркеров — жду подключения...");
                log("(воркеры подключаются автоматически по broadcast)");
                continue;
            }
            log("живых воркеров: " + alive.size());
            for (WorkerRegistry.Entry e : alive) {
                log("  " + e.workerId + "  " + e.baseUrl);
            }

            try {
                renderAndSave(alive);
            } catch (Exception e) {
                log("ошибка рендера: " + e.getMessage());
            }
            // После рендера — снова ждём Enter.
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

    private static void renderAndSave(List<WorkerRegistry.Entry> alive) throws Exception {
        long t0 = System.currentTimeMillis();

        List<RenderTask> tasks = buildTasks();
        log("всего задач (полос): " + tasks.size());

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);

        ExecutorService pool = Executors.newFixedThreadPool(alive.size());
        AtomicInteger nextTask = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);
        final int totalTasks = tasks.size();

        List<Future<?>> futures = new ArrayList<>();
        for (WorkerRegistry.Entry worker : alive) {
            futures.add(pool.submit(() ->
                    workerLoop(worker.baseUrl, tasks, nextTask, completed, totalTasks, image)));
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

    private static List<RenderTask> buildTasks() {
        List<RenderTask> tasks = new ArrayList<>();
        int id = 0;
        for (int y = 0; y < HEIGHT; y += STRIP_HEIGHT) {
            int yEnd = Math.min(y + STRIP_HEIGHT - 1, HEIGHT - 1);
            tasks.add(new RenderTask(id++, y, yEnd,
                    WIDTH, HEIGHT, MAX_ITER,
                    X_MIN, X_MAX, Y_MIN, Y_MAX));
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

    private static void writeMyIpFile() {
        try {
            String localIp = NetUtils.detectLocalIp("8.8.8.8");
            String url = "http://" + localIp + ":" + MASTER_PORT;
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