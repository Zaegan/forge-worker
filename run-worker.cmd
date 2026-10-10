@echo off
rem Runs worker.ps1 without changing Windows' script policy (the bypass applies to this run only).
rem Usage: run-worker.cmd -Server URL -Token TOKEN
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0worker.ps1" %*
