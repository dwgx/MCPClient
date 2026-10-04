---
doc: packet-field-map-1.8.9
title: 1.8.9 packet field-map (PHASE P.9 reference)
layer: reference
status: authoritative
updated: 2026-07-15
parent: PROGRESS.md
read_if: 你要给某个 1.8.9 网络包写/改 PacketSummarizer（P.4），需要它的真类名 + 字段 + getter。
---

# 1.8.9 packet field-map — PHASE P.9

Reference for `PacketSummarizer` implementations (`core/.../flt/seam/summarize/`).
All classes verified from source in this tree: `client/src/main/java/net/minecraft/network/play/{server,client}/*.java`.
FQN packages: server = `net.minecraft.network.play.server`, client = `net.minecraft.network.play.client`.

> **Obfuscation warning.** Some getters keep MCP 1.8.9 `func_XXXXXX_X` names (NOT clean `getX`). A wrong name will not compile. The obfuscated ones are flagged **[OBF]** below.

## Already implemented (P.4, in `HighValueSummarizers`)

| Packet | Dir | Fields surfaced | Getters |
|---|---|---|---|
| `S08PacketPlayerPosLook` | IN | x,y,z,yaw,pitch,relFlags | `getX/getY/getZ():double`, `getYaw/getPitch():float`, `func_179834_f():Set<EnumFlags>` **[OBF]** |
| `S03PacketTimeUpdate` | IN | total, worldTime, tod, cycle | `getTotalWorldTime():long`, `getWorldTime():long` (`<0` = daylight cycle frozen; `abs%24000` = time-of-day) |
| `S00PacketKeepAlive` | IN | id | `func_149134_c():int` **[OBF]** |
| `S23PacketBlockChange` | IN | pos(x,y,z), state | `getBlockPosition():BlockPos` (→ `getX/getY/getZ():int`), `getBlockState():IBlockState` |
| `S02PacketChat` | IN | type, text | `getChatComponent():IChatComponent` (→ `getUnformattedText()`), `getType():byte`, `isChat():boolean` |
| `S26PacketMapChunkBulk` | IN | count, first(cx,cz) | `getChunkCount():int`, `getChunkX(i):int`, `getChunkZ(i):int` |
| `S12PacketEntityVelocity` | IN | eid, v(x,y,z) | `getEntityID():int`, `getMotionX/Y/Z():int` (÷8000.0 = blocks/tick) |
| `C03PacketPlayer` (+`C04`/`C05`/`C06`) | OUT | x,y,z,yaw,pitch,ground,flags | `getPositionX/Y/Z():double`, `getYaw/getPitch():float`, `isOnGround/isMoving/getRotating():boolean` |

The C03 family uses a **prefix fallback** (`handles()` = className startsWith `...C03PacketPlayer`) because C04/C05/C06 are nested classes (`...C03PacketPlayer$C04PacketPlayerPosition`).

## High-value candidates NOT yet summarized (good next P.4 additions)

Verified getters from source; add a summarizer + teeth when needed.

| Packet | Dir | Fields | Getters | Note |
|---|---|---|---|---|
| `S01PacketJoinGame` | IN | entityId, gameType, dimension, difficulty | `getEntityId/getGameType/getDimension/getDifficulty` | session start |
| `S06PacketUpdateHealth` | IN | health, food, saturation | `getHealth():float`, `getFoodLevel():int`, `getSaturationLevel():float` | survival state |
| `S07PacketRespawn` | IN | dimension, difficulty, gameType | `getDimensionID/getDifficulty/getGameType` | dimension change |
| `S09PacketHeldItemChange` | IN | hotbar slot | `getHeldItemHotbarIndex():int` | |
| `S13PacketDestroyEntities` | IN | entity ids | `getEntityIDs():int[]` | |
| `S14PacketEntity` (+`S15`/`S16`/`S17`) | IN | rel-move/look deltas, ground | `func_149062_c/d/e/f/g()` **[OBF]**, `getOnGround():boolean` | register base + subclass fallback |
| `S18PacketEntityTeleport` | IN | eid, x,y,z (÷32.0), yaw,pitch | `getEntityId():int`, `func_149451_c/d/e()` **[OBF]** | fixed-point ÷32 |
| `S0EPacketSpawnObject` | IN | eid, x/y/z (÷32.0), type | `getEntityID():int`, `getX/getY/getZ():int`, `getType():int` | |
| `C0BPacketEntityAction` | OUT | action, aux | `getAction():Action`, `getAuxData():int` | **no** entity-id getter |
| `C08PacketPlayerBlockPlacement` | OUT | pos, face, hand item | `getPosition():BlockPos`, `getPlacedBlockDirection():int`, `getStack():ItemStack` | |
| `C07PacketPlayerDigging` | OUT | status, pos, face | `getPosition():BlockPos`, `getStatus():Action`, `getFacing():EnumFacing` | |

## Rules for writing a summarizer (P.3 contract)

1. Read only **public getters**; never reflect into private fields.
2. Return a short `String`; **never** retain the packet or a mutable member (runs synchronously in the tap; the reference must die with the callback).
3. Never throw — the registry guards calls, but keep field reads defensive (null-check components, wrap risky `toString()`).
4. Keep it cheap: hot packets (velocity, entity-move, chunk) run per-packet on the Netty worker. Surface counts/first-elem, not per-element loops.
5. Fixed-point: entity velocity ÷8000.0 (blocks/tick); spawn/teleport coords ÷32.0 (blocks).
6. Register by exact FQN; use a `startsWith`/superclass fallback only for nested/subclass families (C03, S14).

## Verification

Each summarizer gets a teeth test: construct the packet via its public ctor, run the registry, assert the summary String contains the expected primitive values. This proves the getter names compile AND the values surface. See `PacketSummarizerRegistryTest`.

