package com.example.mandelbrot.common;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Управление правилом Windows Firewall для входящего TCP/UDP-порта.
 *
 * Задача — обеспечить, чтобы приложение могло принимать входящие
 * соединения на своём порту. Windows по умолчанию блокирует
 * входящие соединения к "неизвестным" приложениям.
 *
 * Работает только на Windows. На других ОС — no-op.
 *
 * Класс идемпотентен: повторные вызовы не создают дубликатов.
 * Класс не завершает приложение при неудаче — возвращает статус.
 */
public final class FirewallManager {

    private FirewallManager() {}

    /** Результат операции. */
    public enum Status {
        /** Правило уже есть — ничего не делали. */
        ALREADY_EXISTS,
        /** Правило создано только что. */
        CREATED,
        /** Не Windows — фаервол не актуален. */
        NOT_APPLICABLE,
        /** Пользователь отклонил UAC или нет прав. */
        PERMISSION_DENIED,
        /** Произошла ошибка (PowerShell не найден, таймаут и т.п.). */
        FAILED
    }

    /**
     * Проверяет и при необходимости создаёт правило фаервола.
     *
     * @param ruleName имя правила (например, "Mandelbrot Master 9000")
     * @param port     порт, который нужно разрешить
     * @param protocol "TCP" или "UDP"
     * @param verbose  печатать ли подробные сообщения
     * @return статус операции
     */
    public static Status ensureRule(String ruleName, int port,
                                    String protocol, boolean verbose) {
        if (!isWindows()) {
            if (verbose) System.out.println(
                    "[firewall] не Windows — правило не требуется");
            return Status.NOT_APPLICABLE;
        }

        String proto = normalizeProtocol(protocol);

        // 1. Проверяем, есть ли уже правило.
        if (ruleExists(ruleName)) {
            if (verbose) System.out.println(
                    "[firewall] правило уже существует: " + ruleName);
            return Status.ALREADY_EXISTS;
        }

        // 2. Пытаемся создать правило.
        if (verbose) System.out.println(
                "[firewall] создаю правило: " + ruleName
                        + " (" + proto + " " + port + ")");

        Status status = createRule(ruleName, port, proto, verbose);
        if (status == Status.CREATED) {
            if (verbose) System.out.println(
                    "[firewall] правило создано");
        } else if (status == Status.PERMISSION_DENIED) {
            if (verbose) System.out.println(
                    "[firewall] не удалось: пользователь отклонил запрос или нет прав");
        } else {
            if (verbose) System.out.println(
                    "[firewall] не удалось создать правило (см. вывод выше)");
        }
        return status;
    }

    /**
     * Удобная перегрузка для TCP — обратная совместимость.
     */
    public static Status ensureRule(String ruleName, int port, boolean verbose) {
        return ensureRule(ruleName, port, "TCP", verbose);
    }

    // ============================================================
    //  Внутренняя кухня
    // ============================================================

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    /** Приводим протокол к верхнему регистру, отсекаем мусор. */
    private static String normalizeProtocol(String protocol) {
        if (protocol == null) return "TCP";
        String p = protocol.trim().toUpperCase();
        if (!p.equals("TCP") && !p.equals("UDP")) {
            // Не падаем — просто дефолт.
            return "TCP";
        }
        return p;
    }

    /** Проверка через PowerShell: есть ли правило с таким именем. */
    private static boolean ruleExists(String ruleName) {
        String cmd = "if (Get-NetFirewallRule -DisplayName '"
                + escapeSingle(ruleName)
                + "' -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }";
        int code = runPowerShell(cmd, false);
        return code == 0;
    }

    /**
     * Создаёт правило через PowerShell, запущенный с повышенными правами (UAC).
     */
    private static Status createRule(String ruleName, int port,
                                     String protocol, boolean verbose) {
        // Внутренняя команда PowerShell.
        String inner = "New-NetFirewallRule -DisplayName '"
                + escapeSingle(ruleName)
                + "' -Direction Inbound -Protocol " + protocol
                + " -LocalPort " + port
                + " -Action Allow";

        // Кодируем команду в Base64 (UTF-16LE), чтобы Start-Process
        // не пытался её парсить как свои параметры.
        String encoded = encodeForPowerShell(inner);

        // Start-Process -Verb RunAs → UAC.
        // -EncodedCommand принимает одну строку и не парсит её.
        String outer = "$p = Start-Process powershell "
                + "-Verb RunAs -Wait -PassThru "
                + "-ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', "
                + "'-EncodedCommand', '" + encoded + "'); "
                + "if ($p.ExitCode -eq 0) { exit 0 } else { exit 1 }";

        int code = runPowerShell(outer, verbose);
        if (code == 0) return Status.CREATED;
        return Status.PERMISSION_DENIED;
    }

    /** Кодирует строку в Base64 (UTF-16LE) для -EncodedCommand. */
    private static String encodeForPowerShell(String script) {
        byte[] bytes = script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /** Запускает powershell с командой. Возвращает exit code (-1 при таймауте/ошибке). */
    private static int runPowerShell(String command, boolean verbose) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-Command", command);
            pb.redirectErrorStream(true);
            if (!verbose) {
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }
            Process proc = pb.start();
            boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return -1;
            }
            return proc.exitValue();
        } catch (IOException | InterruptedException e) {
            if (verbose) System.out.println(
                    "[firewall] ошибка запуска PowerShell: " + e.getMessage());
            return -1;
        }
    }

    /** Экранирование одинарных кавычек для PowerShell-строки в одинарных кавычках. */
    private static String escapeSingle(String s) {
        return s.replace("'", "''");
    }
}