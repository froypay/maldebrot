package com.example.mandelbrot.common;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;

/**
 * Утилиты для работы с сетью.
 * Главная задача — узнать свой IP-адрес, который виден "наружу"
 * (тот, по которому до нас дотянется мастер).
 */
public final class NetUtils {

    private NetUtils() {}

    /**
     * Определяет локальный IP-адрес, через который мы видим указанный хост.
     * Классический трюк: открываем UDP-сокет "в никуда" — ядро ОС само
     * выбирает исходящий интерфейс. Никаких пакетов реально не отправляется.
     *
     * @param remoteHost хост, до которого мы якобы идём (обычно — мастер)
     * @return локальный IP или "127.0.0.1", если определить не удалось
     */
    public static String detectLocalIp(String remoteHost) {
        try (DatagramSocket sock = new DatagramSocket()) {
            // Порт 9 (discard) — просто «чтобы был». Реально ничего не отправляем.
            sock.connect(InetAddress.getByName(remoteHost), 9);
            return sock.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * Проверяет, жив ли хост: пытается установить TCP-соединение на порт.
     */
    public static boolean canConnect(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}