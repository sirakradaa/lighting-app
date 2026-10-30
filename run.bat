@echo off
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
  py -3 -m venv .venv
)
call .venv\Scripts\python.exe -m pip install -r requirements.txt
if errorlevel 1 goto fail
call .venv\Scripts\python.exe -m app %*
echo.
echo Lights stopped.
pause
exit /b 0
:fail
echo.
echo Could not install the Bluetooth libraries.
pause
exit /b 1
