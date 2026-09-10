package com.example.mandelbrot.worker;

import com.example.mandelbrot.common.Discovery;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;

/**
 * Слушает UDP broadcast и ищет мастера.
 * Работает до тех пор, пока не найдёт. Блокирующий вызов.
 */
public final class MasterDiscovery {

    private MasterDiscovery() {}

    /**
     * Ждёт broadcast от мастера бесконечно, пока не найдёт.
     * Логирует попытки раз в 5 секунд.
     *
     * @return URL мастера
     */
    public static String discoverForever() {
        System.out.println("[worker] ищу мастера в локальной сети (UDP:"
                + Discovery.DISCOVERY_PORT + ")...");

        try (DatagramSocket socket = new DatagramSocket(Discovery.DISCOVERY_PORT)) {
            socket.setBroadcast(true);
            // Таймаут приёмника — 5 секунд, чтобы периодически писать в лог.
            socket.setSoTimeout(5000);

            byte[] buf = new byte[512];
            long lastLog = 0;

            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                    String msg = new String(packet.getData(),
                            0, packet.getLength(), StandardCharsets.UTF_8);
                    String masterUrl = Discovery.parseAnnouncement(msg);
                    if (masterUrl != null) {
                        System.out.println("[worker] нашёл мастера: " + masterUrl
                                + " (от " + packet.getAddress() + ")");
                        return masterUrl;
                    }
                } catch (java.net.SocketTimeoutException e) {
                    long now = System.currentTimeMillis();
                    if (now - lastLog > 5000) {
                        System.out.println("[worker] мастера пока нет, продолжаю слушать...");
                        lastLog = now;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[worker] ошибка discovery: " + e.getMessage());
            throw new RuntimeException("Discovery failed", e);
        }
    }
}