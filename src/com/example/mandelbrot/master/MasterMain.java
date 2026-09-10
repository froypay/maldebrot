package com.example.mandelbrot.master;

import com.example.mandelbrot.common.RenderResult;
import com.example.mandelbrot.common.RenderTask;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Мастер: раздаёт задачи воркерам, собирает пиксели в BufferedImage,
 * сохраняет PNG.
 *
 * Реализует:
 *   - Master-Worker (Scatter-Gather)
 *   - Data Parallelism
 *   - Health Check
 *   - Work Distribution через AtomicInteger
 *   - Retry (1 раз при сбое воркера)
 */
public final class MasterMain {

    // ---- Конфигурация ----
    static final int WIDTH       = 1600;
    static final int HEIGHT      = 1200;
    static final int MAX_ITER    = 500;
    static final double X_MIN    = -2.5;
    static final double X_MAX    =  1.0;
    static final double Y_MIN    = -1.2;
    static final double Y_MAX    =  1.2;
    static final int STRIP_HEIGHT = 40;

    static final String[] WORKERS = {
            "http://localhost:8081",
            "http://localhost:8082",
            "http://localhost:8083"
    };

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();

        // 1. Health check.
        List<String> alive = new ArrayList<>();
        for (String w : WORKERS) {
            if (ping(w)) {
                alive.add(w);
                log("воркер жив: " + w);
            } else {
                log("воркер МЁРТВ, исключаю: " + w);
            }
        }
        if (alive.isEmpty()) {
            log("Нет живых воркеров — выходим.");
            return;
        }
        log("живых воркеров: " + alive.size());

        // 2. Формируем полосы.
        List<RenderTask> tasks = buildTasks();
        log("всего задач (полос): " + tasks.size());

        // 3. Общий буфер изображения. Запись синхронизируем по нему.
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT,
                BufferedImage.TYPE_INT_RGB);

        // 4. Пул потоков: по одному на воркер.
        ExecutorService pool = Executors.newFixedThreadPool(alive.size());
        AtomicInteger nextTask = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);
        final int totalTasks = tasks.size();

        List<Future<?>> futures = new ArrayList<>();
        for (String workerUrl : alive) {
            futures.add(pool.submit(() ->
                    workerLoop(workerUrl, tasks, nextTask, completed,
                            totalTasks, image)));
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) {
                log("поток воркера упал: " + e.getMessage());
            }
        }
        pool.shutdown();

        long elapsed = System.currentTimeMillis() - t0;
        log("все задачи завершены за " + elapsed + " ms");

        // 5. Сохраняем PNG.
        File out = new File("mandelbrot.png");
        ImageIO.write(image, "png", out);
        log("сохранил " + out.getAbsolutePath()
                + " (" + out.length() / 1024 + " КБ)");
    }

    // ---------- Health check ----------

    private static boolean ping(String baseUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/ping"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req,
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200
                    && resp.body().contains("\"status\":\"ok\"");
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Формирование задач ----------

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

    // ---------- Цикл одного воркера ----------

    private static void workerLoop(String workerUrl,
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
            while (!ok && attempts < 2) { // retry 1 раз
                attempts++;
                try {
                    RenderResult res = callRender(workerUrl, task);
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
                            + " ошибка на " + workerUrl
                            + " (попытка " + attempts + "): " + e.getMessage());
                }
            }
            if (!ok) {
                log("task " + task.taskId + " НЕ ВЫПОЛНЕНА после retry");
                // В учебном проекте просто оставим полосу чёрной.
            }
        }
    }

    // ---------- HTTP-вызов /render ----------

    private static RenderResult callRender(String baseUrl, RenderTask task)
            throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/render"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(task.toJson()))
                .build();
        HttpResponse<String> resp = HTTP.send(req,
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode()
                    + ": " + resp.body());
        }
        return RenderResult.fromJson(resp.body());
    }

    // ---------- Копирование пикселей в общий BufferedImage ----------

    private static void applyResult(BufferedImage image, RenderResult res) {
        byte[] raw = Base64.getDecoder().decode(res.pixelsBase64);
        int rows = res.yEnd - res.yStart + 1;
        int width = res.width;
        if (raw.length != width * rows * 4) {
            throw new IllegalStateException(
                    "Неожиданная длина base64: " + raw.length);
        }
        int[] pixels = new int[width * rows];
        for (int i = 0, j = 0; i < pixels.length; i++) {
            int p = ((raw[j++] & 0xFF) << 24)
                    | ((raw[j++] & 0xFF) << 16)
                    | ((raw[j++] & 0xFF) << 8)
                    |  (raw[j++] & 0xFF);
            pixels[i] = p;
        }
        // Синхронизация по общему изображению.
        synchronized (image) {
            image.setRGB(0, res.yStart, width, rows,
                    pixels, 0, width);
        }
    }

    private static void log(String msg) {
        System.out.println("[master] " + msg);
    }
}