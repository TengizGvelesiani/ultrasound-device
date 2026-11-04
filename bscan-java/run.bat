@echo off
setlocal
cd /d "%~dp0"

if not exist "target\bscan-java-1.0.0.jar" (
    echo Building B-Scan Java application...
    call mvnw.cmd clean package -DskipTests
    if %ERRORLEVEL% neq 0 (
        echo [ERROR] Build failed.
        pause
        exit /b %ERRORLEVEL%
    )
)

echo Starting Ultrasound B-Scan Acquisition System...
java -jar target\bscan-java-1.0.0.jar %*
