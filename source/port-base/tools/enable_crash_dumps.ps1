# enable_crash_dumps.ps1 - turn on full crash dumps for nhllegacy.exe.
#
# Hand this to an end user who is crashing. They run it ONCE (it needs admin),
# reproduce the crash, then send back the .dmp it produces plus the matching
# log from <game>\logs\.
#
# It registers a per-application Windows Error Reporting (WER) LocalDump for
# nhllegacy.exe only - it does not change crash behavior for anything else, and
# `-Off` fully removes it again.
#
# Usage (right-click > Run with PowerShell, or in an admin PowerShell):
#   powershell -ExecutionPolicy Bypass -File enable_crash_dumps.ps1
#   powershell -ExecutionPolicy Bypass -File enable_crash_dumps.ps1 -Off

[CmdletBinding()]
param(
    [string]$DumpFolder = "$env:USERPROFILE\Desktop\NHLLegacy-CrashDumps",
    [int]$DumpCount     = 5,
    [switch]$Off
)

$ErrorActionPreference = 'Stop'
$exe = 'nhllegacy.exe'
$key = "HKLM:\SOFTWARE\Microsoft\Windows\Windows Error Reporting\LocalDumps\$exe"

# --- Must be elevated (writes under HKLM) ---
$admin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    Write-Host "This needs to run as Administrator." -ForegroundColor Yellow
    Write-Host "Re-launching elevated..." -ForegroundColor Yellow
    $argList = @('-ExecutionPolicy','Bypass','-File',"`"$PSCommandPath`"")
    if ($Off)        { $argList += '-Off' }
    if ($DumpFolder) { $argList += @('-DumpFolder',"`"$DumpFolder`"") }
    Start-Process powershell -Verb RunAs -ArgumentList $argList
    return
}

if ($Off) {
    if (Test-Path $key) {
        Remove-Item $key -Recurse -Force
        Write-Host "Crash dumps for $exe DISABLED (registry entry removed)." -ForegroundColor Green
    } else {
        Write-Host "Nothing to remove - crash dumps were not enabled for $exe." -ForegroundColor Green
    }
    return
}

# --- Enable: full dump (DumpType=2) for nhllegacy.exe ---
New-Item -Path $key -Force | Out-Null
New-Item -ItemType Directory -Force -Path $DumpFolder | Out-Null
# ExpandString so %VARS% in the path resolve; DumpType 2 = full memory dump.
New-ItemProperty -Path $key -Name DumpFolder -PropertyType ExpandString -Value $DumpFolder -Force | Out-Null
New-ItemProperty -Path $key -Name DumpType   -PropertyType DWord        -Value 2           -Force | Out-Null
New-ItemProperty -Path $key -Name DumpCount  -PropertyType DWord        -Value $DumpCount  -Force | Out-Null

Write-Host "Crash dumps ENABLED for $exe." -ForegroundColor Green
Write-Host ""
Write-Host "  Dumps will be written to:" -ForegroundColor Cyan
Write-Host "    $DumpFolder"
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Cyan
Write-Host "  1. Launch the game and reproduce the crash."
Write-Host "  2. A file like 'nhllegacy.exe.<pid>.dmp' will appear in that folder."
Write-Host "  3. Send back that .dmp AND the newest log from <game-folder>\logs\."
Write-Host ""
Write-Host "When you're done, you can turn this back off with:" -ForegroundColor DarkGray
Write-Host "  powershell -ExecutionPolicy Bypass -File enable_crash_dumps.ps1 -Off"
