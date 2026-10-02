# Real Skyrim development test

The fake-Skyrim harness is only for protocol/collision bring-up. For the real game,
SkyCraft uses the original C++ SKSE plugin plus the NeoForge 1.21.1 development client.

## Required Skyrim-side dependencies

- Skyrim Special Edition / Anniversary Edition runtime (1.6.x / 1.7.x)
- SKSE64 matching the installed runtime
- Address Library for SKSE Plugins, Anniversary Edition

## Development package layout

Extract the SKSE CI artifact under the repository root as:

```
dev-skse/
└── SKSE/
    └── Plugins/
        ├── SkyCraft.dll
        ├── SkyCraft.pdb
        └── SkyCraft.ini
```

The dev INI has `bStartWithSkyrim = 0`, so the plugin does not launch the upstream
Fabric/Prism bundle.

Then run:

```powershell
powershell -ExecutionPolicy Bypass -File tools\real-skyrim-dev.ps1
```

The script detects a Steam Skyrim install, checks SKSE and Address Library, installs
the dev plugin, launches the NeoForge client, and starts Skyrim through SKSE.
