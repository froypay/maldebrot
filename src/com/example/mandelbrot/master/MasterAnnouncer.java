package com.example.mandelbrot.master;

import com.example.mandelbrot.common.Discovery;
import com.example.mandelbrot.common.NetUtils;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Периодически рассылает broadcast-пакеты с адресом мастера.
 * Работает в отдельном потоке-демоне, не блокирует main.
 */
public final class MasterAnnouncer {

    private final int httpPort;
    private final String myIp;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public MasterAnnouncer(int httpPort, String explicitIp) {
        this.httpPort = httpPort;
        this.myIp = (explicitIp != null && !explicitIp.isBlank())
                ? explicitIp
                : NetUtils.detectLocalIp("8.8.8.8");
    }

    public void start() {
        if (running.getAndSet(true)) return;  // уже запущен
        thread = new Thread(this::loop, "master-announcer");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running.set(false);
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        String msg = Discovery.buildAnnouncement(myIp, httpPort);
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            InetAddress bcAddr = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(
                    data, data.length, bcAddr, Discovery.DISCOVERY_PORT);

            System.out.println("[master] announcer: шлю " + msg
                    + " на 255.255.255.255:" + Discovery.DISCOVERY_PORT);

            while (running.get()) {
                try {
                    socket.send(packet);
                } catch (Exception e) {
                    System.out.println("[master] broadcast ошибка: " + e.getMessage());
                }
                Thread.sleep(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.out.println("[master] announcer упал: " + e.getMessage());
        }
    }
}