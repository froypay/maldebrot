@echo off
chcp 65001 >nul
REM run-worker.cmd [master-url]
set MASTER=%1
if "%MASTER%"=="" set MASTER=http://192.168.1.10:9000
cd /d "%~dp0dist\MandelbrotWorker"
MandelbrotWorker.exe --master %MASTER%
pause