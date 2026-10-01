@echo off
setlocal
cd /d "%~dp0.."

where py >nul 2>&1
if %errorlevel%==0 (
    set "PY=py -3"
) else (
    where python >nul 2>&1
    if not %errorlevel%==0 (
        echo.
        echo Python 3 was not found.
        echo Install Python 3, then run this file again.
        echo.
        pause
        exit /b 1
    )
    set "PY=python"
)

echo.
echo [1/2] Starting the fake Skyrim bridge...
start "SkyCraft fake Skyrim" cmd /k "%PY% tools\fake_skyrim.py 180"

echo [2/2] Starting Minecraft 1.21.1 NeoForge...
echo The first Gradle run can download Minecraft/NeoForge dependencies.
echo.
cd neoforge
call ..\fabric\gradlew.bat runClient

endlocal
