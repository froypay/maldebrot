package com.example.mandelbrot.common;

/**
 * Протокол обнаружения мастера через UDP broadcast.
 *
 * Мастер периодически шлёт broadcast-пакет:
 *   "MANDELBROT_MASTER|1|<ip>|<port>"
 * Воркер слушает и, поймав пакет, извлекает URL мастера.
 */
public final class Discovery {

    private Discovery() {}

    /** UDP-порт для discovery. Отдельный от HTTP-порта мастера. */
    public static final int DISCOVERY_PORT = 9001;

    /** Магический префикс — чтобы не путать с чужими пакетами. */
    public static final String MAGIC = "MANDELBROT_MASTER";

    /** Версия протокола. */
    public static final int VERSION = 1;

    /** Формирует сообщение мастера для broadcast. */
    public static String buildAnnouncement(String masterIp, int masterPort) {
        return MAGIC + "|" + VERSION + "|" + masterIp + "|" + masterPort;
    }

    /**
     * Парсит сообщение. Возвращает URL мастера, если это наше сообщение,
     * иначе null.
     */
    public static String parseAnnouncement(String msg) {
        if (msg == null) return null;
        String trimmed = msg.trim();
        String[] parts = trimmed.split("\\|");
        if (parts.length != 4) return null;
        if (!MAGIC.equals(parts[0])) return null;
        try {
            int version = Integer.parseInt(parts[1]);
            if (version != VERSION) return null;
            String ip = parts[2];
            int port = Integer.parseInt(parts[3]);
            return "http://" + ip + ":" + port;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}