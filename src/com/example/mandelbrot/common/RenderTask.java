package com.example.mandelbrot.common;

/**
 * DTO задачи: одна горизонтальная полоса изображения.
 * Сериализуется/десериализуется вручную (см. Json.java).
 *
 * Поля:
 *   taskId     — номер задачи (для логов и идемпотентности)
 *   yStart     — первая строка полосы (включительно)
 *   yEnd       — последняя строка полосы (включительно)
 *   width      — ширина всего изображения
 *   height     — высота всего изображения (для проверок)
 *   maxIter    — максимум итераций z = z^2 + c
 *   xMin,xMax  — границы области по X
 *   yMin,yMax  — границы области по Y
 */
public final class RenderTask {
    public final int taskId;
    public final int yStart;
    public final int yEnd;
    public final int width;
    public final int height;
    public final int maxIter;
    public final double xMin;
    public final double xMax;
    public final double yMin;
    public final double yMax;

    public RenderTask(int taskId, int yStart, int yEnd,
                      int width, int height, int maxIter,
                      double xMin, double xMax,
                      double yMin, double yMax) {
        this.taskId = taskId;
        this.yStart = yStart;
        this.yEnd = yEnd;
        this.width = width;
        this.height = height;
        this.maxIter = maxIter;
        this.xMin = xMin;
        this.xMax = xMax;
        this.yMin = yMin;
        this.yMax = yMax;
    }

    /** Сколько строк в полосе. */
    public int rows() {
        return yEnd - yStart + 1;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"taskId\":").append(taskId).append(',');
        sb.append("\"yStart\":").append(yStart).append(',');
        sb.append("\"yEnd\":").append(yEnd).append(',');
        sb.append("\"width\":").append(width).append(',');
        sb.append("\"height\":").append(height).append(',');
        sb.append("\"maxIter\":").append(maxIter).append(',');
        sb.append("\"xMin\":").append(xMin).append(',');
        sb.append("\"xMax\":").append(xMax).append(',');
        sb.append("\"yMin\":").append(yMin).append(',');
        sb.append("\"yMax\":").append(yMax);
        sb.append('}');
        return sb.toString();
    }

    public static RenderTask fromJson(String json) {
        Json j = Json.parse(json);
        return new RenderTask(
                (int) j.getLong("taskId"),
                (int) j.getLong("yStart"),
                (int) j.getLong("yEnd"),
                (int) j.getLong("width"),
                (int) j.getLong("height"),
                (int) j.getLong("maxIter"),
                j.getDouble("xMin"),
                j.getDouble("xMax"),
                j.getDouble("yMin"),
                j.getDouble("yMax")
        );
    }
}