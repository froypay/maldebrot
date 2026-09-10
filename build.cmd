@echo off
REM build.cmd — compile src/ and build mandelbrot-distributed.jar
setlocal

cd /d "%~dp0"

echo [build] Cleaning out\ and old jar...
if exist out rmdir /s /q out
if exist sources.txt del /q sources.txt
if exist mandelbrot-distributed.jar del /q mandelbrot-distributed.jar

echo [build] Compiling...
mkdir out
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -d out @sources.txt
if errorlevel 1 (
    echo [build] COMPILE ERROR
    exit /b 1
)

echo [build] Building jar...
jar --create --file mandelbrot-distributed.jar ^
    --main-class com.example.mandelbrot.master.MasterMain ^
    -C out .
if errorlevel 1 (
    echo [build] JAR ERROR
    exit /b 1
)

echo [build] Done: mandelbrot-distributed.jar
exit /b 0