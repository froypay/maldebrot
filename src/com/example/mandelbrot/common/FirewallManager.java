package com.example.mandelbrot.common;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Управление правилом Windows Firewall для входящего TCP-порта.
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
     * @param port     TCP-порт, который нужно разрешить
     * @param verbose  печатать ли подробные сообщения
     * @return статус операции
     */
    public static Status ensureRule(String ruleName, int port, boolean verbose) {
        if (!isWindows()) {
            if (verbose) System.out.println(
                    "[firewall] не Windows — правило не требуется");
            return Status.NOT_APPLICABLE;
        }

        // 1. Проверяем, есть ли уже правило.
        if (ruleExists(ruleName)) {
            if (verbose) System.out.println(
                    "[firewall] правило уже существует: " + ruleName);
            return Status.ALREADY_EXISTS;
        }

        // 2. Пытаемся создать правило.
        if (verbose) System.out.println(
                "[firewall] создаю правило: " + ruleName + " (порт " + port + ")");

        Status status = createRule(ruleName, port, verbose);
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

    // ============================================================
    //  Внутренняя кухня
    // ============================================================

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
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
     * Возвращает:
     *   CREATED — если команда прошла успешно;
     *   PERMISSION_DENIED — если пользователь отклонил UAC;
     *   FAILED — если что-то другое.
     */
    private static Status createRule(String ruleName, int port, boolean verbose) {
        // Внутренняя команда PowerShell, которую нужно выполнить от админа.
        // Кавычки внутри аккуратно экранируем.
        String inner = "New-NetFirewallRule -DisplayName '"
                + escapeSingle(ruleName)
                + "' -Direction Inbound -Protocol TCP -LocalPort "
                + port
                + " -Action Allow";

        // Start-Process -Verb RunAs — это UAC-повышение.
        // -Wait — ждём завершения, чтобы узнать exit code.
        // -PassThru + $p.ExitCode — узнаём, чем закончилось.
        String outer =
                "$p = Start-Process powershell -Verb RunAs -Wait -PassThru -ArgumentList "
                        + "'-NoProfile', '-Command', \""
                        + inner.replace("\"", "`\"")
                        + "\"; "
                        + "if ($p.ExitCode -eq 0) { exit 0 } else { exit 1 }";

        int code = runPowerShell(outer, verbose);
        if (code == 0) return Status.CREATED;
        // Start-Process -Verb RunAs при отказе от UAC возвращает код 1
        // или выбрасывает исключение, которое тоже даёт ненулевой exit.
        // Точную причину не всегда можно различить, поэтому:
        return Status.PERMISSION_DENIED;
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