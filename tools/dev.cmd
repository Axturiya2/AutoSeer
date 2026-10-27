@echo off
rem PowerShell / cmd 入口：轉給 Git Bash 執行 dev.sh（用法同 tools/dev.sh）
"C:\Program Files\Git\bin\bash.exe" "%~dp0dev.sh" %*
