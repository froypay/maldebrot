@echo off
chcp 65001 >nul
REM ============================================================
REM  setup-worker.cmd
REM  Запускается ОДИН РАЗ на каждой воркер-машине.
REM  Делает:
REM    1) собирает jar
REM    2) собирает app-image через jpackage
REM    3) кладёт рядом с exe удобный start-worker.cmd
REM  Фаервол настраивается самим приложением при первом запуске.
REM ============================================================
setlocal

echo ============================================================
echo   УСТАНОВКА ВОРКЕРА
echo ============================================================
echo.

REM ---------- 1. Проверка JDK ----------
where javac >nul 2>&1
if errorlevel 1 (
    echo [ОШИБКА] javac не найден. Установите JDK 17+ и добавьте в PATH.
    pause
    exit /b 1
)
where jpackage >nul 2>&1
if errorlevel 1 (
    echo [ОШИБКА] jpackage не найден. Нужен JDK 17+.
    pause
    exit /b 1
)

REM ---------- 2. Сборка jar ----------
echo [1/3] Сборка jar...
call "%~dp0build.cmd"
if errorlevel 1 (
    echo [ОШИБКА] Сборка jar не удалась.
    pause
    exit /b 1
)
echo.

REM ---------- 3. jpackage ----------
echo [2/3] Упаковка воркера через jpackage...

set PROJECT_DIR=%~dp0
set DIST_DIR=%PROJECT_DIR%dist
set INPUT_DIR=%PROJECT_DIR%build-input
set JAR_FILE=%PROJECT_DIR%mandelbrot-distributed.jar

if not exist "%JAR_FILE%" (
    echo [ОШИБКА] Не найден jar: %JAR_FILE%
    pause
    exit /b 1
)

if exist "%INPUT_DIR%" rmdir /s /q "%INPUT_DIR%"
mkdir "%INPUT_DIR%"
copy /Y "%JAR_FILE%" "%INPUT_DIR%\" >nul

if exist "%DIST_DIR%\MandelbrotWorker" rmdir /s /q "%DIST_DIR%\MandelbrotWorker"

jpackage ^
  --type app-image ^
  --name MandelbrotWorker ^
  --input "%INPUT_DIR%" ^
  --main-jar mandelbrot-distributed.jar ^
  --main-class com.example.mandelbrot.worker.WorkerMain ^
  --add-modules java.base,java.desktop,java.net.http,jdk.httpserver ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --dest "%DIST_DIR%" ^
  --app-version 1.0.0 ^
  --win-console

if errorlevel 1 (
    echo [ОШИБКА] jpackage не удался.
    pause
    exit /b 1
)

rmdir /s /q "%INPUT_DIR%"

REM ---------- 4. start-worker.cmd ----------
echo [3/3] Создаю start-worker.cmd рядом с exe...

set WORKER_APP_DIR=%PROJECT_DIR%dist\MandelbrotWorker

if not exist "%WORKER_APP_DIR%" (
    echo [ОШИБКА] Папка не создана: %WORKER_APP_DIR%
    pause
    exit /b 1
)

(
    echo @echo off
    echo cd /d "%%~dp0"
    echo MandelbrotWorker.exe %%*
    echo pause
) > "%WORKER_APP_DIR%\start-worker.cmd"

echo   создан: %WORKER_APP_DIR%\start-worker.cmd

echo.
echo ============================================================
echo   ГОТОВО
echo ============================================================
echo.
echo   Воркер установлен в: dist\MandelbrotWorker
echo.
echo   Запуск:
echo     dist\MandelbrotWorker\start-worker.cmd
echo   или двойной клик на MandelbrotWorker.exe
echo.
echo   Воркер сам найдёт мастера в локальной сети через broadcast.
echo   IP вводить не нужно.
echo.
echo   Если Windows спросит про доступ к сети — разрешите
echo   для "Частных сетей".
echo.
pause
endlocal