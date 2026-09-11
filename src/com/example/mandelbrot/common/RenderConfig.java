package com.example.mandelbrot.common;

import java.util.Scanner;

/**
 * Конфигурация рендера фрактала Мандельброта.
 *
 * Отвечает за:
 *   - хранение параметров рендера;
 *   - разбор CLI-аргументов;
 *   - интерактивный диалог с пользователем (если CLI не задан);
 *   - проверку корректности.
 *
 * Используется мастером для формирования RenderTask.
 */
public final class RenderConfig {

    // ---------- Поля ----------
    public int width;
    public int height;
    public int maxIter;
    public int stripHeight;

    public double xMin, xMax;
    public double yMin, yMax;

    /** Антиалиасинг: 1 = выключен, 2 = 2×2, 3 = 3×3, 4 = 4×4. */
    public int antiAliasing;

    // ---------- Значения по умолчанию ----------
    public static RenderConfig defaults() {
        RenderConfig c = new RenderConfig();
        c.width        = 1600;
        c.height       = 1200;
        c.maxIter      = 500;
        c.stripHeight  = 40;
        c.xMin = -2.5; c.xMax = 1.0;
        c.yMin = -1.2; c.yMax = 1.2;
        c.antiAliasing = 1;
        return c;
    }

    /** Краткое описание одной строкой. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(width).append('x').append(height);
        sb.append(", maxIter=").append(maxIter);
        sb.append(", strip=").append(stripHeight);
        sb.append(", окно=[").append(xMin).append("..").append(xMax)
                .append("]x[").append(yMin).append("..").append(yMax).append(']');
        if (antiAliasing > 1) sb.append(", AA=").append(antiAliasing).append('x');
        return sb.toString();
    }

    /** Оценка: сколько всего пикселей с учётом AA. */
    public long totalPixels() {
        long base = (long) width * height;
        return base * antiAliasing * antiAliasing;
    }

    /** Оценка времени рендера в секундах (грубо). */
    public double estimateSeconds(int workers) {
        // Эмпирика: ~1.5 мкс на пиксель-итерацию при MAX_ITER=500.
        // На самом деле зависит от машины, но для учебного проекта ок.
        double pixelIterations = (double) width * height
                * maxIter * antiAliasing * antiAliasing;
        double secondsOnOneCore = pixelIterations * 1.5e-6;
        return secondsOnOneCore / Math.max(1, workers);
    }

    // ============================================================
    //  CLI-парсинг
    // ============================================================

    /**
     * Если в args есть хотя бы один из наших флагов — парсим и возвращаем.
     * Если нет — возвращаем null (значит, нужен интерактивный ввод).
     */
    public static RenderConfig fromArgsOrNull(String[] args) {
        RenderConfig c = defaults();
        boolean anyRecognized = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--width"      -> { c.width       = Integer.parseInt(args[++i]); anyRecognized = true; }
                case "--height"     -> { c.height      = Integer.parseInt(args[++i]); anyRecognized = true; }
                case "--max-iter"   -> { c.maxIter     = Integer.parseInt(args[++i]); anyRecognized = true; }
                case "--strip"      -> { c.stripHeight = Integer.parseInt(args[++i]); anyRecognized = true; }
                case "--x-min"      -> { c.xMin        = Double.parseDouble(args[++i]); anyRecognized = true; }
                case "--x-max"      -> { c.xMax        = Double.parseDouble(args[++i]); anyRecognized = true; }
                case "--y-min"      -> { c.yMin        = Double.parseDouble(args[++i]); anyRecognized = true; }
                case "--y-max"      -> { c.yMax        = Double.parseDouble(args[++i]); anyRecognized = true; }
                case "--aa"         -> { c.antiAliasing = Integer.parseInt(args[++i]); anyRecognized = true; }
                default -> {
                    // не наш флаг — пропускаем, его разберёт MasterMain
                }
            }
        }
        return anyRecognized ? c : null;
    }

    /** Проверка корректности. Возвращает null, если всё ок, иначе текст ошибки. */
    public String validate() {
        if (width < 16 || width > 32000) return "width должен быть 16..32000";
        if (height < 16 || height > 32000) return "height должен быть 16..32000";
        if (maxIter < 10 || maxIter > 1_000_000) return "max-iter должен быть 10..1000000";
        if (stripHeight < 1 || stripHeight > height) return "strip должен быть 1..height";
        if (antiAliasing < 1 || antiAliasing > 4) return "aa должен быть 1..4";
        if (xMin >= xMax) return "x-min должен быть < x-max";
        if (yMin >= yMax) return "y-min должен быть < y-max";
        return null;
    }

    // ============================================================
    //  Presets
    // ============================================================

    /** Готовые наборы. */
    public enum Preset {
        FAST    ("Быстро (для проверки)",     800,   600,   200,  40, 1),
        NORMAL  ("Обычно (стандарт)",         1600,  1200,  500,  40, 1),
        NICE    ("Красиво",                   3200,  2400,  2000, 40, 2),
        DETAIL  ("Детально (долго)",          4000,  3000,  5000, 30, 2),
        MAXIMUM ("Максимум (очень долго)",    8000,  6000, 10000, 20, 2);

        public final String label;
        public final int width;
        public final int height;
        public final int maxIter;
        public final int stripHeight;
        public final int aa;

        Preset(String label, int width, int height, int maxIter, int stripHeight, int aa) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.maxIter = maxIter;
            this.stripHeight = stripHeight;
            this.aa = aa;
        }
    }

    /** Применить preset (только размеры, maxIter, strip, aa; окно не меняется). */
    public void applyPreset(Preset p) {
        this.width       = p.width;
        this.height      = p.height;
        this.maxIter     = p.maxIter;
        this.stripHeight = p.stripHeight;
        this.antiAliasing = p.aa;
    }

    // ============================================================
    //  Интерактивный ввод
    // ============================================================

    /**
     * Спрашивает у пользователя параметры рендера.
     * Enter — оставить текущее значение. "q" — выход.
     */
    public static RenderConfig interactive(Scanner scanner) {
        RenderConfig c = defaults();

        System.out.println();
        System.out.println("=== Настройка рендера ===");
        System.out.println("Enter — оставить значение по умолчанию.");
        System.out.println("q — выход.");
        System.out.println();

        // Preset?
        System.out.println("Preset'ы:");
        Preset[] presets = Preset.values();
        for (int i = 0; i < presets.length; i++) {
            System.out.printf("  %d) %-30s %dx%d, maxIter=%d, AA=%dx%n",
                    i + 1, presets[i].label,
                    presets[i].width, presets[i].height,
                    presets[i].maxIter, presets[i].aa);
        }
        System.out.print("Выберите preset [1-" + presets.length + "] (Enter = обычный): ");
        String line = scanner.nextLine().trim();
        if ("q".equalsIgnoreCase(line)) return null;
        if (!line.isEmpty()) {
            try {
                int idx = Integer.parseInt(line) - 1;
                if (idx >= 0 && idx < presets.length) {
                    c.applyPreset(presets[idx]);
                    System.out.println("Применён preset: " + presets[idx].label);
                }
            } catch (NumberFormatException ignored) {}
        }

        System.out.println();
        System.out.println("--- Точные настройки (Enter = значение из preset'а) ---");

        c.width       = askInt(scanner, "Ширина", c.width, 16, 32000);
        c.height      = askInt(scanner, "Высота", c.height, 16, 32000);
        c.maxIter     = askInt(scanner, "MAX_ITER (глубина)", c.maxIter, 10, 1_000_000);
        c.stripHeight = askInt(scanner, "Высота полосы (strip)", c.stripHeight, 1, c.height);
        c.antiAliasing= askInt(scanner, "Антиалиасинг (1=выкл, 2=2x2, 3=3x3, 4=4x4)",
                c.antiAliasing, 1, 4);

        System.out.println();
        System.out.println("--- Окно фрактала (Enter = стандартное) ---");
        c.xMin = askDouble(scanner, "X_MIN", c.xMin);
        c.xMax = askDouble(scanner, "X_MAX", c.xMax);
        c.yMin = askDouble(scanner, "Y_MIN", c.yMin);
        c.yMax = askDouble(scanner, "Y_MAX", c.yMax);

        String err = c.validate();
        if (err != null) {
            System.out.println("[!] Ошибка ввода: " + err);
            return null;
        }

        System.out.println();
        System.out.println("Итог: " + c.describe());
        System.out.println("Пикселей (с AA): " + c.totalPixels());
        return c;
    }

    private static int askInt(Scanner sc, String label, int def, int min, int max) {
        while (true) {
            System.out.printf("%s [%d]: ", label, def);
            String line = sc.nextLine().trim();
            if (line.isEmpty()) return def;
            try {
                int v = Integer.parseInt(line);
                if (v < min || v > max) {
                    System.out.println("  Значение вне диапазона " + min + ".." + max);
                    continue;
                }
                return v;
            } catch (NumberFormatException e) {
                System.out.println("  Не число, попробуйте снова");
            }
        }
    }

    private static double askDouble(Scanner sc, String label, double def) {
        while (true) {
            System.out.printf("%s [%s]: ", label, def);
            String line = sc.nextLine().trim();
            if (line.isEmpty()) return def;
            try {
                return Double.parseDouble(line.replace(',', '.'));
            } catch (NumberFormatException e) {
                System.out.println("  Не число, попробуйте снова");
            }
        }
    }
}