@echo off
setlocal
powershell -ExecutionPolicy Bypass -File "%~dp0stop-all.ps1" %*
endlocal
