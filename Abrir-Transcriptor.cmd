@echo off
setlocal

cd /d "%~dp0"

echo Iniciando Transcriptor Local...
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo La aplicacion no pudo iniciarse. Codigo de salida: %EXIT_CODE%
    echo Revisa el mensaje mostrado arriba y vuelve a intentarlo.
    pause
)

endlocal
