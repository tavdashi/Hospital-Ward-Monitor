@echo off
setlocal enabledelayedexpansion

title Hospital Ward Management System

echo.
echo  +======================================================+
echo  ^|       IOT Hospital Ward Management System            ^|
echo  ^|       Serial Bridge Launcher v1.1                    ^|
echo  +======================================================+
echo.

set "BASE_DIR=%~dp0"
set "JAVA_SRC=%BASE_DIR%java\src"
set "JAVA_OUT=%BASE_DIR%java\bin"
set "LIB_DIR=%BASE_DIR%java\lib"
set "JSERIAL_JAR=%LIB_DIR%\jserialcomm-2.10.4.jar"

echo [1/4] Checking Java installation...
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found in PATH.
    echo         Download JDK from: https://adoptium.net/
    pause
    exit /b 1
)
echo        Java found.

echo [2/4] Locating javac compiler...
set "JAVAC_CMD=javac"
javac -version >nul 2>&1
if errorlevel 1 (
    for /f "delims=" %%i in ('where java 2^>nul') do (
        if not defined FOUND_JAVA set "FOUND_JAVA=%%i"
    )
    if defined FOUND_JAVA (
        set "JAVAC_CMD=!FOUND_JAVA:java.exe=javac.exe!"
        echo        Using javac at: !JAVAC_CMD!
    ) else (
        echo [ERROR] javac not found. Install a full JDK, not just JRE.
        echo         https://adoptium.net/
        pause
        exit /b 1
    )
) else (
    echo        javac found on PATH.
)

echo [3/4] Checking jSerialComm library...
if not exist "%JSERIAL_JAR%" (
    echo.
    echo  [MISSING] jserialcomm-2.10.4.jar not found.
    echo.
    echo  1. Go to: https://github.com/Fazecast/jSerialComm/releases
    echo  2. Download jserialcomm-2.10.4.jar
    echo  3. Place it in: %LIB_DIR%\
    echo.
    pause
    exit /b 1
)
echo        jSerialComm found.

echo [4/4] Compiling Java bridge...
if not exist "%JAVA_OUT%" mkdir "%JAVA_OUT%"
"%JAVAC_CMD%" -cp "%JSERIAL_JAR%" -d "%JAVA_OUT%" "%JAVA_SRC%\SimpleJSON.java" "%JAVA_SRC%\HospitalWardBridge.java"
if errorlevel 1 (
    echo.
    echo [ERROR] Compilation failed. See errors above.
    pause
    exit /b 1
)
echo        Compilation successful.

set "COM_ARG="
if not "%1"=="" (
    set "COM_ARG=%1"
    echo.
    echo  COM port specified: %1
) else (
    echo.
    echo  No COM port given - auto-detecting Arduino...
    echo  To specify manually: run.bat COM6
)

echo.
echo  ======================================================
echo   Hospital Ward Bridge starting...
echo   Dashboard : http://localhost:8765/dashboard
echo   Status API: http://localhost:8765/api/status
echo   Press Ctrl+C to stop.
echo  ======================================================
echo.

start "" /b cmd /c "timeout /t 3 >nul && start http://localhost:8765/dashboard"

java -cp "%JAVA_OUT%;%JSERIAL_JAR%" HospitalWardBridge %COM_ARG%

echo.
echo  Bridge stopped.
pause
