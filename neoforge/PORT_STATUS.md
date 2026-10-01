# SkyCraft NeoForge 1.21.1 port

## Current status

CI is green on Windows + Java 21.

Working/compiled now:

- NeoForge 1.21.1 mod bootstrap
- Java 21/JNA implementation of the existing `Local\\SkyCraft_v1` shared-memory protocol
- binary-layout test against the SKSE protocol
- Skyrim heartbeat/link detection
- Skyrim state -> Minecraft state reading
- Minecraft player state -> Skyrim writing (movement authority deliberately disabled)
- automatic dedicated `SkyCraft` mirror-world creation
- mirror dimension/world preset for 1.21.1
- Skyrim collision-ring consumer
- invisible 1/8-height collision proxy blocks in the mirror world

The proxy collision is intentionally coarse for the first Create/Sable milestone. Sable caches physics collision by Minecraft `BlockState`, so the proxy's height is encoded directly in its state instead of using position-dependent shapes.

## Safety during bring-up

`MC_IN_WORLD` remains clear for now. Skyrim therefore stays authoritative and an incomplete Minecraft collision/runtime cannot move the Skyrim player yet.

## Next milestones

1. Runtime test with `tools\\test-neoforge.cmd`.
2. Teleport/hold the mirror player at Skyrim's coordinates once nearby collision has arrived.
3. Port input forwarding and vanilla player collision/placement.
4. Add the exact Create 6.0.10 + Sable 2.x + Aeronautics 1.3.x development stack.
5. Replace SkyCraft's vanilla-only world-mesh export with Minecraft framebuffer + depth passthrough so Flywheel/Sable/Aeronautics moving sublevels render correctly in Skyrim.
