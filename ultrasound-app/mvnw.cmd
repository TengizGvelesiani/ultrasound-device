@echo off
setlocal

where mvn >nul 2>nul
if %ERRORLEVEL% equ 0 (
    mvn %*
    goto :end
)

for /d %%I in ("C:\Program Files\JetBrains\IntelliJ IDEA*") do (
    if exist "%%I\plugins\maven\lib\maven3\bin\mvn.cmd" (
        call "%%I\plugins\maven\lib\maven3\bin\mvn.cmd" %*
        goto :end
    )
    if exist "%%I\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" (
        call "%%I\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" %*
        goto :end
    )
)

if exist "C:\Program Files\JetBrains\IntelliJ IDEA 2023.2.2\plugins\maven\lib\maven3\bin\mvn.cmd" (
    call "C:\Program Files\JetBrains\IntelliJ IDEA 2023.2.2\plugins\maven\lib\maven3\bin\mvn.cmd" %*
    goto :end
)

echo [ERROR] Maven not found on PATH or in IntelliJ directory. Please ensure Maven is installed.
exit /b 1

:end
exit /b %ERRORLEVEL%
