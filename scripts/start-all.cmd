@echo off
setlocal
powershell -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" %*
endlocal
