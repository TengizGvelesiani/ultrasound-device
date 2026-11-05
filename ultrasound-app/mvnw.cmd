@echo off
setlocal

where mvn >nul 2>nul
if %ERRORLEVEL% equ 0 (
    mvn %*
    exit /b %ERRORLEVEL%
)

for /d %%I in ("C:\Program Files\JetBrains\IntelliJ IDEA*") do (
    if exist "%%I\plugins\maven\lib\maven3\bin\mvn.cmd" (
        call "%%I\plugins\maven\lib\maven3\bin\mvn.cmd" %*
        exit /b %ERRORLEVEL%
    )
    if exist "%%I\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" (
        call "%%I\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" %*
        exit /b %ERRORLEVEL%
    )
)

if exist "C:\Program Files\JetBrains\IntelliJ IDEA 2023.2.2\plugins\maven\lib\maven3\bin\mvn.cmd" (
    call "C:\Program Files\JetBrains\IntelliJ IDEA 2023.2.2\plugins\maven\lib\maven3\bin\mvn.cmd" %*
    exit /b %ERRORLEVEL%
)

echo [ERROR] Maven not found on PATH or in IntelliJ directory. Please ensure Maven is installed.
exit /b 1
