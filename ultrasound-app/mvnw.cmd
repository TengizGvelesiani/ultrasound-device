@echo off
setlocal

where mvn >nul 2>nul
if %ERRORLEVEL% equ 0 (
    mvn %*
    exit /b %ERRORLEVEL%
)

set ERRORLEVEL=0
if exist "C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" (
    "C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" %*
    exit /b %ERRORLEVEL%
)

echo [ERROR] Maven not found on PATH or in IntelliJ directory. Please ensure Maven is installed.
exit /b 1
