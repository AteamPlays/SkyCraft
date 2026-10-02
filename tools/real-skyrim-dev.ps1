param(
    [string]$SkyrimDir = "",
    [switch]$InstallOnly
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
$skse = Join-Path $game "skse64_loader.exe"
if (-not (Test-Path $skse)) {
    throw "SKSE64 is not installed next to SkyrimSE.exe in: $game"
}

$plugins = Join-Path $game "Data\SKSE\Plugins"
$addressLibrary = Get-ChildItem $plugins -Filter "versionlib-*.bin" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $addressLibrary) {
    throw "Address Library for SKSE Plugins (Anniversary Edition) was not detected in Data\SKSE\Plugins."
}

$devPlugin = Join-Path $root "dev-skse\SKSE\Plugins\SkyCraft.dll"
$devIni = Join-Path $root "dev-skse\SKSE\Plugins\SkyCraft.ini"
if (-not (Test-Path $devPlugin)) {
    throw "The real-Skyrim SkyCraft DLL is not staged under dev-skse\SKSE\Plugins yet."
}

New-Item -ItemType Directory -Force $plugins | Out-Null
Copy-Item $devPlugin (Join-Path $plugins "SkyCraft.dll") -Force
Copy-Item $devIni (Join-Path $plugins "SkyCraft.ini") -Force

Write-Host ""
Write-Host "SkyCraft real-Skyrim dev setup"
Write-Host "  Skyrim:  $game"
Write-Host "  Runtime: $(Get-FileVersionText (Join-Path $game \"SkyrimSE.exe\"))"
Write-Host "  SKSE:     $(Get-FileVersionText $skse)"
Write-Host "  Plugin:   installed"
Write-Host "  Address Library: $($addressLibrary.Name)"
Write-Host ""

if ($InstallOnly) {
    Write-Host "Install-only requested; not launching."
    exit 0
}

$mc = Join-Path $root "tools\launch-neoforge-real.cmd"
Start-Process "cmd.exe" -ArgumentList "/k", $mc -WorkingDirectory $root
Start-Sleep -Seconds 2
Write-Host "Launching Skyrim through SKSE..."
Start-Process $skse -WorkingDirectory $game
