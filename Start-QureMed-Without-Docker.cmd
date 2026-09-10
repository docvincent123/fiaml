@echo off
setlocal
cd /d "%~dp0"

net session >nul 2>&1
if not "%errorlevel%"=="0" (
  echo Requesting administrator rights...
  powershell.exe -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Setup-NoDocker.ps1"
set "QUREMED_EXIT=%errorlevel%"
if not "%QUREMED_EXIT%"=="0" (
  echo.
  echo QureMed could not start. Send a photo of this window to support.
  pause
)
exit /b %QUREMED_EXIT%
