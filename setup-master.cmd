@echo off
chcp 65001 >nul
REM ============================================================
REM  setup-master.cmd
REM  Запускается ОДИН РАЗ на мастер-машине.
REM  Делает всё:
REM    1) собирает jar
REM    2) собирает app-image через jpackage
REM    3) разрешает порт 9000 в фаерволе Windows
REM    4) кладёт рядом с exe удобный start-master.cmd
REM ============================================================
setlocal

echo ============================================================
echo   УСТАНОВКА МАСТЕРА
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
echo [1/4] Сборка jar...
call "%~dp0build.cmd"
if errorlevel 1 (
    echo [ОШИБКА] Сборка jar не удалась.
    pause
    exit /b 1
)
echo.

REM ---------- 3. jpackage ----------
echo [2/4] Упаковка мастера через jpackage...

set PROJECT_DIR=%~dp0
set DIST_DIR=%PROJECT_DIR%dist
set INPUT_DIR=%PROJECT_DIR%build-input
set JAR_FILE=%PROJECT_DIR%mandelbrot-distributed.jar

REM Проверка: jar на месте?
if not exist "%JAR_FILE%" (
    echo [ОШИБКА] Не найден jar: %JAR_FILE%
    pause
    exit /b 1
)

REM Готовим чистую папку ввода — только jar внутри.
if exist "%INPUT_DIR%" rmdir /s /q "%INPUT_DIR%"
mkdir "%INPUT_DIR%"
copy /Y "%JAR_FILE%" "%INPUT_DIR%\" >nul

echo [debug] INPUT_DIR = %INPUT_DIR%
echo [debug] DIST_DIR  = %DIST_DIR%

REM Удаляем только папку мастера, не весь dist.
if exist "%DIST_DIR%\MandelbrotMaster" rmdir /s /q "%DIST_DIR%\MandelbrotMaster"

jpackage ^
  --type app-image ^
  --name MandelbrotMaster ^
  --win-console ^
  --input "%INPUT_DIR%" ^
  --main-jar mandelbrot-distributed.jar ^
  --main-class com.example.mandelbrot.master.MasterMain ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --dest "%DIST_DIR%" ^
  --app-version 1.0.0

if errorlevel 1 (
    echo [ОШИБКА] jpackage не удался.
    pause
    exit /b 1
)

REM Чистим временную папку.
rmdir /s /q "%INPUT_DIR%"

REM ---------- 4. Фаервол ----------
echo [3/4] Настройка фаервола Windows (порт 9000)...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$rule = Get-NetFirewallRule -DisplayName 'Mandelbrot Master' -ErrorAction SilentlyContinue; if ($rule) { Write-Host '  правило уже существует' } else { New-NetFirewallRule -DisplayName 'Mandelbrot Master' -Direction Inbound -Protocol TCP -LocalPort 9000 -Action Allow | Out-Null; Write-Host '  правило добавлено' }"
echo.

REM ---------- 5. Удобный запуск ----------
echo [4/4] Создаю start-master.cmd рядом с exe...

set MASTER_APP_DIR=%PROJECT_DIR%dist\MandelbrotMaster

if not exist "%MASTER_APP_DIR%" (
    echo [ОШИБКА] Папка не создана: %MASTER_APP_DIR%
    pause
    exit /b 1
)

(
    echo @echo off
    echo cd /d "%%~dp0"
    echo MandelbrotMaster.exe %%*
    echo pause
) > "%MASTER_APP_DIR%\start-master.cmd"

echo   создан: %MASTER_APP_DIR%\start-master.cmd
echo.
echo ============================================================
echo   ГОТОВО!
echo ============================================================
echo.
echo   Мастер установлен в: dist\MandelbrotMaster\
echo.
echo   Запуск:
echo     dist\MandelbrotMaster\start-master.cmd
echo   или двойной клик на MandelbrotMaster.exe
echo.
echo   Свой IP можно будет увидеть после запуска в:
echo     master-ip.txt
echo   (файл создастся рядом с рабочей папкой мастера)
echo.
pause
endlocal