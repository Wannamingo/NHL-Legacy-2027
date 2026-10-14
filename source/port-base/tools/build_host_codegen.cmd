@echo off
setlocal
if not defined NHL_ANDROID_TOOLS set "NHL_ANDROID_TOOLS=C:\Users\user\Documents\Codex\2026-10-02\new-chat\work\toolchains"
"C:\Users\user\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" "%~dp0build_android.py" --host-codegen
exit /b %errorlevel%
