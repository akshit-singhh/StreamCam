@echo off
title StreamCam Pro - Receiver Launcher
echo ===================================================
echo      StreamCam Pro - Receiver Setup ^& Launcher
echo ===================================================
echo.
echo [*] Checking and installing dependencies...
pip install -q -r requirements.txt
echo [*] Dependencies up to date.
echo.
echo What would you like to do?
echo 1. Run Receiver Instantly (Recommended)
echo 2. Build Standalone .exe (PyInstaller)
echo 3. Exit
echo.

set /p choice="Enter your choice (1-3): "

if "%choice%"=="1" goto run
if "%choice%"=="2" goto build
if "%choice%"=="3" goto end

:run
cls
echo [*] Starting StreamCam Receiver...
python receiver.py
pause
goto end

:build
cls
echo [*] Building StreamCam Receiver Executable...
echo [*] This will take a moment. Please wait...
python -m PyInstaller --noconsole receiver.py
echo.
echo [*] Build Complete! You can find the executable in the "dist\receiver" folder.
pause
goto end

:end
exit