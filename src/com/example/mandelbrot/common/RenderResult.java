package com.example.mandelbrot.common;

/**
 * DTO ответа воркера: base64-строка с RGB-пикселями полосы
 * (упакованы как int[] -> byte[] построчно) + метаданные.
 */
public final class RenderResult {
    public final int taskId;
    public final int yStart;
    public final int yEnd;
    public final int width;
    public final long renderMs;
    public final String pixelsBase64;

    public RenderResult(int taskId, int yStart, int yEnd,
                        int width, long renderMs, String pixelsBase64) {
        this.taskId = taskId;
        this.yStart = yStart;
        this.yEnd = yEnd;
        this.width = width;
        this.renderMs = renderMs;
        this.pixelsBase64 = pixelsBase64;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(pixelsBase64.length() + 128);
        sb.append('{');
        sb.append("\"taskId\":").append(taskId).append(',');
        sb.append("\"yStart\":").append(yStart).append(',');
        sb.append("\"yEnd\":").append(yEnd).append(',');
        sb.append("\"width\":").append(width).append(',');
        sb.append("\"renderMs\":").append(renderMs).append(',');
        sb.append("\"pixelsBase64\":\"").append(pixelsBase64).append('"');
        sb.append('}');
        return sb.toString();
    }

    public static RenderResult fromJson(String json) {
        Json j = Json.parse(json);
        return new RenderResult(
                (int) j.getLong("taskId"),
                (int) j.getLong("yStart"),
                (int) j.getLong("yEnd"),
                (int) j.getLong("width"),
                j.getLong("renderMs"),
                j.getString("pixelsBase64")
        );
    }
}