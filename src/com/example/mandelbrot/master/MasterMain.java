package com.example.mandelbrot.master;

import com.example.mandelbrot.common.Json;
import com.example.mandelbrot.common.RenderResult;
import com.example.mandelbrot.common.RenderTask;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import com.example.mandelbrot.common.NetUtils;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Мастер — координатор распределённого рендера.
 *
 * Роли:
 *   1) HTTP-сервер, к которому подключаются воркеры (register + heartbeat).
 *   2) Раздатчик задач: берёт зарегистрированных живых воркеров
 *      и распределяет между ними полосы фрактала.
 *   3) Сборщик: копирует полученные пиксели в общий BufferedImage.
 *
 * Паттерны: Master-Worker, Scatter-Gather, Data Parallelism, Health Check,
 *           Work Distribution через AtomicInteger, Retry.
 */
public final class MasterMain {

    // ---------- Конфигурация рендера ----------
    static final int WIDTH        = 8000;
    static final int HEIGHT       = 6000;
    static final int MAX_ITER     = 2000;
    static final double X_MIN     = -2.5;
    static final double X_MAX     =  1.0;
    static final double Y_MIN     = -1.2;
    static final double Y_MAX     =  1.2;
    static final int STRIP_HEIGHT = 40;

    // ---------- Конфигурация сети ----------
    /** Порт, на котором мастер слушает воркеров. */
    static final int MASTER_PORT  = 9000;
    /** Сколько ждать воркеров перед автостартом рендера (сек). */
    static final int WAIT_WORKERS_SEC = 15;
    /** Через сколько миллисекунд без heartbeat воркер считается мёртвым. */
    static final long WORKER_TIMEOUT_MS = 10_000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final WorkerRegistry REGISTRY = new WorkerRegistry(WORKER_TIMEOUT_MS);

    public static void main(String[] args) throws Exception {
        log("стартую HTTP-сервер мастера на порту " + MASTER_PORT);
        startHttpServer(MASTER_PORT);

        // Пишем свой IP в файл, чтобы воркеры знали, куда подключаться.
        writeMyIpFile();

        log("жду воркеров... (автостарт рендера через " + WAIT_WORKERS_SEC + " с)");
        waitForWorkers(WAIT_WORKERS_SEC);

        List<WorkerRegistry.Entry> alive = REGISTRY.alive();
        if (alive.isEmpty()) {
            log("Нет живых воркеров — выходим.");
            System.exit(1);
        }
        log("живых воркеров: " + alive.size());
        for (WorkerRegistry.Entry e : alive) {
            log("  " + e.workerId + "  " + e.baseUrl);
        }

        renderAndSave(alive);
        log("готово, мастер завершает работу");
        System.exit(0);
    }

    // ============================================================
    //  HTTP-сервер мастера: /register, /heartbeat, /workers
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
            log("зарегистрирован воркер: " + workerId + " @ " + baseUrl
                    + "  (всего: " + REGISTRY.size() + ")");
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
            String workerId = j.getString("workerId");
            String baseUrl  = j.getString("baseUrl");
            boolean wasKnown = REGISTRY.all().stream()
                    .anyMatch(e -> e.workerId.equals(workerId));
            REGISTRY.heartbeat(workerId, baseUrl);
            if (!wasKnown) {
                log("воркер переоткрылся после падения: " + workerId);
            }
            send(ex, 200, "{\"status\":\"ok\"}");
        } catch (Exception e) {
            send(ex, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private static void handleWorkers(HttpExchange ex) throws IOException {
        // Простой отладочный ответ: список воркеров, JSON-массив вручную.
        StringBuilder sb = new StringBuilder();
        sb.append("{\"workers\":[");
        List<WorkerRegistry.Entry> all = REGISTRY.all();
        long now = System.currentTimeMillis();
        for (int i = 0; i < all.size(); i++) {
            WorkerRegistry.Entry e = all.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"workerId\":\"").append(e.workerId).append("\",");
            sb.append("\"baseUrl\":\"").append(e.baseUrl).append("\",");
            sb.append("\"ageMs\":").append(now - e.lastSeenMs).append('}');
        }
        sb.append("]}");
        send(ex, 200, sb.toString());
    }

    // ============================================================
    //  Ожидание воркеров
    // ============================================================

    private static void waitForWorkers(int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            REGISTRY.prune();
            List<WorkerRegistry.Entry> alive = REGISTRY.alive();
            if (!alive.isEmpty()) {
                log("уже есть живые воркеры: " + alive.size());
                // Не выходим сразу — даём шанс подключиться остальным.
            }
            Thread.sleep(1000);
        }
    }

    // ============================================================
    //  Сам рендер
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

    /**
     * Пишет в master-ip.txt строку вида:
     *   http://192.168.1.10:9000
     * которую нужно передавать воркерам: --master <эта-строка>
     *
     * Файл кладём в домашнюю папку пользователя (user.home) — туда
     * точно есть права на запись, и вы всегда его найдёте,
     * независимо от того, откуда запущен мастер.
     */
    private static void writeMyIpFile() {
        try {
            // Определяем локальный IP, через который нас видят другие машины.
            String localIp = NetUtils.detectLocalIp("8.8.8.8");
            String url = "http://" + localIp + ":" + MASTER_PORT;

            // Кладём файл в C:\Users\<имя>\
            String userHome = System.getProperty("user.home");
            Path file = Path.of(userHome, "master-ip.txt");
            Files.writeString(file, url + System.lineSeparator());

            log("мой IP для воркеров: " + url);
            log("записал в " + file.toAbsolutePath());

            // Дублируем в рабочую директорию (рядом с exe, если запущено через start-master.cmd).
            try {
                Path cwdFile = Path.of("master-ip.txt").toAbsolutePath();
                Files.writeString(cwdFile, url + System.lineSeparator());
                log("дубликат:   " + cwdFile);
            } catch (Exception ignored) {
                // Нет прав — не страшно, главный файл уже в user.home.
            }
        } catch (Exception e) {
            log("не удалось записать master-ip.txt: " + e.getMessage());
        }
    }
}