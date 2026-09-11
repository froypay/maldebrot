package com.example.mandelbrot.common;

/**
 * Общая логика рендера фрактала Мандельброта.
 * Используется и воркером (при рендере полосы), и мастером
 * (для проверки/отладки). Чистая функция — идемпотентна.
 */
public final class MandelbrotRenderer {

    private MandelbrotRenderer() {}

    /**
     * Рендерит одну горизонтальную полосу.
     *
     * @return int[] длины width * rows(), где каждый элемент — RGB (0xRRGGBB),
     *         строки идут подряд: сначала строка yStart, затем yStart+1 и т.д.
     */
    public static int[] renderStrip(RenderTask task) {
        return renderStrip(task, 1);
    }

    public static int[] renderStrip(RenderTask task, int aa) {
        int width = task.width;
        int rows = task.rows();
        int[] pixels = new int[width * rows];

        double dx = (task.xMax - task.xMin) / (width - 1);
        double dy = (task.yMax - task.yMin) / (task.height - 1);

        int idx = 0;
        for (int y = task.yStart; y <= task.yEnd; y++) {
            double cy = task.yMin + y * dy;
            for (int x = 0; x < width; x++) {
                double cx = task.xMin + x * dx;
                if (aa <= 1) {
                    pixels[idx++] = mandelbrotColor(cx, cy, task.maxIter);
                } else {
                    pixels[idx++] = mandelbrotColorAA(cx, cy, task.maxIter,
                            dx, dy, aa);
                }
            }
        }
        return pixels;
    }

    /**
     * Классическая формула z = z^2 + c.
     * Возвращает RGB-цвет пикселя.
     */
    /** 1 выборка. */
    private static int mandelbrotColor(double cx, double cy, int maxIter) {
        double zx = 0.0, zy = 0.0;
        double zx2 = 0.0, zy2 = 0.0;
        int iter = 0;

        while (zx2 + zy2 <= 4.0 && iter < maxIter) {
            zy = 2.0 * zx * zy + cy;
            zx = zx2 - zy2 + cx;
            zx2 = zx * zx;
            zy2 = zy * zy;
            iter++;
        }

        if (iter >= maxIter) return 0x000000;

        double logZn = Math.log(zx2 + zy2) / 2.0;
        double nu = Math.log(logZn / Math.log(2.0)) / Math.log(2.0);
        double t = iter + 1.0 - nu;
        if (t < 0) t = 0;
        if (t > maxIter) t = maxIter;

        double hue = (t / 32.0) % 1.0;
        double saturation = 0.85;
        double value = Math.sqrt(t / (double) maxIter);
        return hsvToRgb(hue, saturation, value);
    }

    /** N×N выборок с усреднением. */
    private static int mandelbrotColorAA(double cx, double cy, int maxIter,
                                         double dx, double dy, int aa) {
        int r = 0, g = 0, b = 0;
        int n = aa * aa;
        for (int i = 0; i < aa; i++) {
            for (int j = 0; j < aa; j++) {
                double ox = (i + 0.5) / aa - 0.5;
                double oy = (j + 0.5) / aa - 0.5;
                double sx = cx + ox * dx;
                double sy = cy + oy * dy;
                int c = mandelbrotColor(sx, sy, maxIter);
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
            }
        }
        return ((r / n) << 16) | ((g / n) << 8) | (b / n);
    }

    /** HSV -> RGB. h,s,v в [0..1]. Возвращает 0xRRGGBB. */
    public static int hsvToRgb(double h, double s, double v) {
        if (h < 0) h = 0;
        if (h > 1) h = h - Math.floor(h);
        if (s < 0) s = 0;
        if (s > 1) s = 1;
        if (v < 0) v = 0;
        if (v > 1) v = 1;

        double c = v * s;
        double hp = h * 6.0;
        double x = c * (1 - Math.abs((hp % 2.0) - 1.0));

        double r1, g1, b1;
        if (hp < 1)      { r1 = c; g1 = x; b1 = 0; }
        else if (hp < 2) { r1 = x; g1 = c; b1 = 0; }
        else if (hp < 3) { r1 = 0; g1 = c; b1 = x; }
        else if (hp < 4) { r1 = 0; g1 = x; b1 = c; }
        else if (hp < 5) { r1 = x; g1 = 0; b1 = c; }
        else             { r1 = c; g1 = 0; b1 = x; }

        double m = v - c;
        int r = (int) Math.round((r1 + m) * 255.0);
        int g = (int) Math.round((g1 + m) * 255.0);
        int b = (int) Math.round((b1 + m) * 255.0);
        return (r << 16) | (g << 8) | b;
    }
}