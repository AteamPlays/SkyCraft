param(
    [string]$SkyrimDir = "",
    [switch]$InstallOnly,
    [switch]$CheckOnly
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Find-Skyrim {
    param([string]$Explicit)

    if ($Explicit) {
        $p = (Resolve-Path $Explicit).Path
        if (Test-Path (Join-Path $p "SkyrimSE.exe")) { return $p }
        throw "SkyrimSE.exe not found in $p"
    }

    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($key in @("HKCU:\Software\Valve\Steam", "HKLM:\SOFTWARE\WOW6432Node\Valve\Steam")) {
        try {
            $steam = (Get-ItemProperty $key -ErrorAction Stop).SteamPath
            if ($steam) {
                $candidates.Add((Join-Path $steam "steamapps\common\Skyrim Special Edition"))
                $vdf = Join-Path $steam "steamapps\libraryfolders.vdf"
                if (Test-Path $vdf) {
                    foreach ($line in Get-Content $vdf) {
                        if ($line -match '"path"\s+"([^"]+)"') {
                            $lib = $Matches[1].Replace("\\", "\")
                            $candidates.Add((Join-Path $lib "steamapps\common\Skyrim Special Edition"))
                        }
                    }
                }
            }
        } catch {}
    }

    foreach ($p in $candidates | Select-Object -Unique) {
        if (Test-Path (Join-Path $p "SkyrimSE.exe")) { return (Resolve-Path $p).Path }
    }

    throw "Could not find Skyrim automatically. Re-run with -SkyrimDir pointing at the Skyrim Special Edition folder."
}

function Get-FileVersionText([string]$Path) {
    try { return (Get-Item $Path).VersionInfo.FileVersion } catch { return "unknown" }
}

$game = Find-Skyrim $SkyrimDir
$skyrimExe = Join-Path $game "SkyrimSE.exe"
$skse = Join-Path $game "skse64_loader.exe"
$plugins = Join-Path $game "Data\SKSE\Plugins"
$addressLibrary = Get-ChildItem $plugins -Filter "versionlib-*.bin" -ErrorAction SilentlyContinue | Select-Object -First 1

$skseInstalled = Test-Path $skse
$addressInstalled = $null -ne $addressLibrary

Write-Host ""
Write-Host "SkyCraft real-Skyrim environment"
Write-Host "  Skyrim:          $game"
Write-Host "  Skyrim runtime:  $(Get-FileVersionText $skyrimExe)"
Write-Host "  SKSE loader:     $(if ($skseInstalled) { Get-FileVersionText $skse } else { 'MISSING' })"
Write-Host "  Address Library: $(if ($addressInstalled) { $addressLibrary.Name } else { 'MISSING' })"
Write-Host ""

if (-not $skseInstalled -or -not $addressInstalled) {
    Write-Host "Missing prerequisites:"
    if (-not $skseInstalled) {
        Write-Host "  - SKSE64 matching the Skyrim runtime above"
    }
    if (-not $addressInstalled) {
        Write-Host "  - Address Library for SKSE Plugins"
    }
    if ($CheckOnly) {
        exit 2
    }
    throw "Install the missing Skyrim prerequisites, then run the check again."
}

if ($CheckOnly) {
    Write-Host "Environment check passed."
    exit 0
}

$devPlugin = Join-Path $root "dev-skse\SKSE\Plugins\SkyCraft.dll"
$devIni = Join-Path $root "dev-skse\SKSE\Plugins\SkyCraft.ini"
if (-not (Test-Path $devPlugin)) {
    throw "The real-Skyrim SkyCraft DLL is not staged under dev-skse\SKSE\Plugins yet."
}

New-Item -ItemType Directory -Force $plugins | Out-Null
Copy-Item $devPlugin (Join-Path $plugins "SkyCraft.dll") -Force
Copy-Item $devIni (Join-Path $plugins "SkyCraft.ini") -Force
Write-Host "SkyCraft.dll installed into Data\SKSE\Plugins."

if ($InstallOnly) {
    Write-Host "Install-only requested; not launching."
    exit 0
}

$mc = Join-Path $root "tools\launch-neoforge-real.cmd"
Start-Process "cmd.exe" -ArgumentList "/k", $mc -WorkingDirectory $root
Start-Sleep -Seconds 2
Write-Host "Launching Skyrim through SKSE..."
Start-Process $skse -WorkingDirectory $game
