---
doc: known-issues
title: Known Issues
layer: reference
status: authoritative
updated: 2026-07-15b
parent: ../README.md
read_if: 只在你遇到已知缺陷(如 mipmap 蓝斑/远景接缝),或要恢复某个被 defer 的调查、或准备登记新缺陷时读。
---
# Known Issues

Tracked defects that are non-blocking and deferred. Each entry records what was
verified, ruled out, and the most promising fix direction — so a future session
can resume without re-investigating from scratch.

---

## KI-1 — Distant sky-blue vertical seam cracks at block edges (was mis-filed as "uninitialized-mipmap speckles")

**Status:** OPEN — deferred as WON'T-FIX-NOW (cosmetic, distant-only). **2026-07-15 ultracode investigation REFUTED the original "uninitialized mipmap VRAM" diagnosis** and every texture/sampling-layer fix. Real cause is a geometry/UV seam, not a texture-data bug; a real fix is a render-pipeline change whose cost far exceeds the benefit for a distance-only cosmetic artifact. See "2026-07-15 investigation" below before touching this again.
**History:** OPEN → FIX-APPLIED-in-client(2026-07-11,glGetTexImage readback 0/64)→ reverted → 2026-07-14 zero-fill compat patch **MCP-KI0001** armed → **2026-07-15 ultracode: patch proven INEFFECTIVE live, original diagnosis refuted, root cause re-identified as geometry/UV seam.**
**Severity:** cosmetic (distant/angled terrain), does not affect gameplay, protocol, or single/multiplayer. Invisible up close; only visible at distance with Mipmap Levels > 0.

### 2026-07-15 ultracode investigation — what was RULED OUT and the corrected diagnosis
Full live + offline campaign (2 research workflows, live MCP `eval_java` GPU probes marshalled to the game thread, offline atlas reconstruction). **Conclusions, evidence-backed:**

- **Real artifact color = RGB(150,180,255) / (140,160,233) — bright SKY color**, not a texture color. Measured from the actual crack screenshot, pixels concentrated in narrow vertical columns (20-28 px each) = thin vertical lines on block edges. So the cracks are the sky/fog clear color showing THROUGH at block-face seams, distance-only, mipmap>0 only.
- **The zero-fill patch (MCP-KI0001) does NOTHING for the visible artifact.** Live: 2 patches armed, cracks still present. Falsifies the "uninitialized NULL-alloc VRAM" hypothesis outright (its own docstring's "0/64 nonzero readback" proved only that gap texels are zero, which is irrelevant to the visible seam).
- **RULED OUT by live hot-load A/B (all had ZERO visible effect):** ① mip `GL_TEXTURE_MAX_LEVEL`/`MAX_LOD` clamp to 2 and 3; ② forcing coarse-level transparent texels opaque (rewrote 16661 texels L2-L4); ③ `GL_TEXTURE_LOD_BIAS` +1.0; ④ atlas wrap `GL_REPEAT`→`GL_CLAMP_TO_EDGE`.
- **RULED OUT — anisotropic filtering.** Atlas read back `MAX_ANISOTROPY=16` (driver-forced; our code/shim set AF nowhere). User set NVIDIA Control Panel AF=Off for the JBR java.exe profile → cracks unchanged. (Note: the per-texture `0x84FE` readback stays 16 even after driver-off; it's the allowed ceiling, not the used value — an unreliable probe.)
- **RULED OUT — gamma-2.2 mip blend (`blendColors`).** Offline: single sprites mipped with vanilla gamma-2.2 vs plain linear averaging produce IDENTICAL result — neither injects the artifact. So `blendColors` is not the cause.
- **RULED OUT — atlas tile-bleeding at coarse mips.** MC generates mips PER-SPRITE before stitching (`TextureMap.loadTextureAtlas:179`), and `Stitcher.getMipmapDimension` rounds every slot to a multiple of `1<<mipmapLevels`(=16). Offline atlas reconstruction: the only "cold-blue" texels in the packed atlas trace to genuinely-blue sprites (`ice_packed`, `wool_colored_light_blue`, `flower_allium`, `diamond_ore`), NOT to sprite borders. The 0.2%-cold-blue signal that looked like a lead was a false positive (an over-broad pixel threshold catching real blue blocks).
- **Corrected root cause (high confidence on category, exact mechanism not nailed):** a GEOMETRY/UV seam at block-face boundaries — adjacent block faces (or the fixed `TextureAtlasSprite.initSprite` UV inset `0.01/atlasW`, which does NOT scale with mip level: covers 0.01 texel at L0, 0.0006 at L4) let distant sampling fall between faces and reveal the sky clear color. This is a render-mesh/precision issue, NOT texture data. Fixing it = stitcher gutter/border-replication + mip-scaled UV inset, or a terrain-mesh seam fix — a pipeline change, not a param tweak.
- **DECISION (dwgx, 2026-07-15):**物理上非纹理层可低成本解决 → 归档 WON'T-FIX-NOW. If ever revisited: do the ONE decisive live depth probe (is the seam pixel at a different depth than the face = geometry gap, vs same depth = UV) before writing any code. Do NOT re-attempt texture/sampling fixes — all exhausted above.
- **MCP-KI0001 status:** stays armed (harmless, signed) but is now known-ineffective for the visible artifact; it only zero-fills alloc gaps. Consider disarming or re-scoping when a real fix lands.

### (SUPERSEDED — original 2026-07-14 diagnosis, kept for history) WATER 表现面 + uninitialized-VRAM theory
**First seen:** in-world on a real display (superflat and normal worlds). Blue
(sky-colored) speckles scattered on grass, worsening with distance; colored
seams between distant blocks. Only visible on a real GPU/display — not
reproducible headless.

### WATER 表现面（2026-07-14 实机探测,同一根源不同表现）
在真机世界(RTX 5070 Ti)转动镜头看水面,水面散布**白色小点/短划**,视角/距离变化时明显——这是 mipmap
采样的签名(更高 mip 级被采到)。经 MCP `eval_java`(marshal 到游戏线程)实机探测确认:
`game_mipmapLevels=4`、min_filter=`0x2702 NEAREST_MIPMAP_LINEAR`(用 mip 采样)、profileMask=2
COMPAT(排除 core-profile 破坏假设,与 KI-1 原结论一致)。**判定:与草地蓝斑同一根源**——
`allocateTextureImpl` null 指针分配每个 mip 级 → NVIDIA 驱动不清零 → atlas 未写入区域是垃圾内存,
水面区域采样成白点(草地区域采样成蓝斑,颜色差异 = atlas 不同区域的垃圾值不同)。**同一颗 zero-fill
mip 补丁(MCP-KI0001)应一并修复水面波纹**;落地后一并 live 验证。
**诚实边界**:探针是 tick 间隙的 GL 状态快照(采到 bound_tex=0 空绑定),**未**在"画水面那次 draw"
帧内抓到确切纹理绑定 —— 视觉 + mip 设置 + 角度依赖三者收敛强烈指向 KI-1,但帧内 hook 才是铁证。
**探测教训(记给下一个 AI)**:`eval_java`/`eval_ephemeral` 里的 GL 调用**必须** marshal 到游戏线程
(`GameBridge.onGameThread(Callable, timeoutMillis)`)—— LWJGL3 比 LWJGL2 严格,在 worker 线程裸调
`glGetString` 等会触发 `No context is current` 的 **native abort 拖垮整个游戏进程**(本次已踩,游戏被探崩一次)。
`eval_ephemeral`(C7 hidden class)对含匿名 Callable 的探针会 `defineHiddenClass failed`;用 `eval_java`
(普通 DynamicClassLoader)+ `public Object run()` + 内部 marshal 才稳。

### Confirmed by investigation
- **It IS the mipmap path.** Setting Mipmap Levels = 0 in Video Settings makes the
  speckles disappear entirely (user-verified). So the fault is in mipmap
  generation, upload, or how LWJGL3 samples the generated mip levels.
- **The Java algorithm is byte-for-byte identical to vanilla 1.8.9.**
  `TextureUtil.generateMipmapData` and `blendColors` (the alpha-weighted,
  gamma-correct 2x2 downscale with the `/4.0F` divisor and `alpha<96 -> 0` cutoff)
  matched the untouched MavenMCP-1.8.9 base exactly (the `_analyze_base` reference
  clone that verification used has since been deleted). This is Mojang's original
  logic, NOT a decompile error and NOT introduced by this port. It renders
  correctly under real Minecraft / LWJGL2, so the algorithm itself is fine.
- **Upload path checked and correct:** `allocateTextureImpl` allocates all mip
  levels (`for i in 0..maxLevel` with `glTexImage2D`, `GL_TEXTURE_MAX_LEVEL`/
  `MIN_LOD`/`MAX_LOD`/`LOD_BIAS` set); `uploadTextureMipmap`/`uploadTextureSub`
  upload each level with `glTexSubImage2D` + `GL_BGRA` + `GL_UNSIGNED_INT_8_8_8_8_REV`
  + native-order `IntBuffer` (all valid, unchanged in LWJGL3).
- **Ruled out:** R/B channel swap (format unchanged), `glPixelStorei` state (none
  used, same as vanilla), buffer position/limit (`copyToBufferPos` correct),
  ContextCapabilities shim (mip path is uncalled by caps gating in 1.8.9).

### Conclusion
The defect lives at the **LWJGL3 context / driver interaction** layer, not in the
port's Java code. Correct vanilla calls produce wrong pixels only under our LWJGL3
context — a classic LWJGL2→3 mipmap porting issue also seen in other ports.

### LIVE-VERIFIED 2026-07-11 (first cross-live-gap check)
**结论:真因 = 未初始化的 mip-level GPU 内存;`PROFILE_MASK=2`(compatibility)排除了 core-profile 假设(方向#2)。修法:zero-init 每个 mip 级。** 以下是证据。

Launched the real client (NVIDIA RTX 5070 Ti, GL_VERSION=4.6.0 NVIDIA 596.13) with
the MCP Core agent, connected over the REST facade, and ran a GL probe via
`eval_java` on the game thread. Result: `GL_CONTEXT_PROFILE_MASK = 2` =
`GL_CONTEXT_COMPATIBILITY_PROFILE_BIT` (not core) — empirically rules out
the "core-profile context breaks GL_TEXTURE_MAX_LEVEL/LOD" hypothesis (fix
direction #2 below). The remaining cause is
uninitialized mip-level GPU memory — `allocateTextureImpl` allocates each mip
with a null pointer (`glTexImage2D(..., (IntBuffer)null)`); modern NVIDIA drivers
don't zero it, so atlas regions never written at level i read back garbage that
samples blue/cyan at distance. Fix: zero-init each mip level (row-batched
`glTexSubImage2D`, reusing the existing 4M dataBuffer). This was also the moment the
project's core premise first held in reality: an LLM (over MCP) read the live GL
context of a running, rendering MC client.

### FIX APPLIED 2026-07-11 (later REVERTED — see status)
> This fix was applied to `client/` source and live-proven, then **reverted** to keep `client/` pure vanilla. The description below records what the fix WAS; it is no longer in the tree. Re-implementation lands as signed compat patch MCP-KI0001.

`TextureUtil.allocateTextureImpl` (`client/.../texture/TextureUtil.java:195`) now
zero-fills every mip level right after the null-pointer `glTexImage2D` allocation,
via a new `zeroFillMipLevel(level, w, h)` helper that row-batches transparent-black
(BGRA 0) through the shared 4M-int `dataBuffer` — same pattern as the sprite upload
path, no new allocation, no change to real sprite data (level 0 is overwritten by
the normal upload afterward). This removes the only source of uninitialized texels.
- Regression: `client/.../MipZeroFillCoverageTest` (4 tests) verifies the row-batch
  arithmetic covers exactly w*h texels per level with no gap/overlap/dropped partial
  batch — the off-by-one risk. The GPU effect itself can't be unit-tested headless
  (TextureUtil static-init binds GL), so this guards the loop logic; the visual proof
  is the live re-verification below. (client was 17/17 green at the time; this test was reverted with the fix — client is now 14, pure vanilla.)
### PROOF ON REAL GPU 2026-07-11 (mechanism-level, no world needed)
Relaunched the client with the fixed jars (RTX 5070 Ti). Via MCP `eval_java` on the
game thread: generated a texture, ran the FIXED `allocateTextureImpl(id, 4, 64, 64)`
(5 mip levels), then read mip level 3 (8×8 = 64 texels) straight back off the GPU
with `glGetTexImage(GL_TEXTURE_2D, 3, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buf)`.
Result: **`nonzero=0/64, first=0x0` → every texel is 0 (transparent black).** Before
the fix that memory was whatever the driver left (uninitialized → garbage that
samples blue at distance). This is the direct causal proof: the speckle source
(uninitialized mip texels sampled at range) is gone — those texels are now
transparent-black, not garbage. Confirmed the patched `zeroFillMipLevel(III)V` is
loaded in the live JVM (via `find_method`), and it runs during the 512×512
texture-atlas creation logged at every startup.

The only thing NOT done is a human looking at distant grass in a loaded world (blocked
only by the deleted 1.8 asset bundle, not by code). The mechanism is proven; a visual
spot-check would be confirmatory, not load-bearing.

### Fix directions to try (future session)
1. **Study exact fixes in comparable ports** (lwjgl3ify, moehreag/Zarzelcow
   legacy-lwjgl3, MCP-Reborn) for "grass blue speckle / mipmap seam on LWJGL3" —
   find their concrete code change, not general advice.
2. **Verify the actual GL context**: after `GL.createCapabilities()` in
   `lwjgl2-shim .../opengl/Display.java`, log `glGetString(GL_VERSION)` and
   `GL_CONTEXT_PROFILE_MASK`. If the driver handed a core/forward-compat context
   instead of 3.2 compatibility, fixed-function + `GL_TEXTURE_MAX_LEVEL`/LOD and
   the `_8_8_8_8_REV` path can misbehave. Display.java requests
   `GLFW_OPENGL_COMPAT_PROFILE` on non-mac; confirm it's honored.
3. **Atlas power-of-two check**: confirm each sprite + atlas dimension is a
   multiple of `2^mipLevels`; non-multiples make mip halving read out of bounds
   at edges = colored speckles.
4. **Cannot be unit-tested headless**: `TextureUtil.<clinit>` calls `glGenTextures`
   (needs a live GL context), so `generateMipmapData` can't be exercised in a
   forked surefire JVM without a display.

### Related files
- `client/src/main/java/net/minecraft/client/renderer/texture/TextureUtil.java`
  (generateMipmapData ~49, blendColors ~98, uploadTextureSub ~167,
  allocateTextureImpl ~200)
- `lwjgl2-shim/src/main/java/org/lwjgl/opengl/Display.java` (context creation, ~150-177)
- `client/src/main/java/net/minecraft/client/settings/GameSettings.java` (mipmapLevels)

### Changes already made (correct, but do NOT fix KI-1)
- `TextureUtil.java:58` — `p_147949_2_.length` → `p_147949_2_[0].length` (a real
  latent dimension bug in the transparent-texel scan; present in vanilla too).
- `TextureUtil.java:~249` — `GL_CLAMP` → `GL12.GL_CLAMP_TO_EDGE` (core-profile
  correctness for clamped textures/GUI; unrelated to grass).

---

## KI-2 — HTTP facade had no auth + no non-loopback bind guard (FIXED)

**Status:** RESOLVED (fixed in the L6/HTTP/GUI feature batch).
**Severity (before fix):** high — network blast radius if misconfigured.

A concurrent gap survey found the REST facade (`HttpFacade`) answered every route
(including read-only ones) with **zero authentication**, and `McpCore` passed
`-Dmcp.core.httpBind` straight through with **no loopback guard**. Since the
facade routes to the same supervised registry that runs R-1 arbitrary code
(`eval_java`, `redefine_class`, C5 field write, C6 JVMTI), binding `0.0.0.0` would
have exposed arbitrary code execution to the whole network unauthenticated —
violating the already-written SECURITY.md invariant ("must add auth before ANY
non-loopback bind"), which code did not enforce.

### Fix
- `HttpFacade` gained an optional bearer token (4-arg ctor); when set, every route
  requires `Authorization: Bearer <token>`, compared in constant time
  (`MessageDigest.isEqual`). Blank token = the intentional frictionless loopback
  dev posture (unchanged).
- `McpCore` now refuses to start the facade on a non-loopback bind without
  `-Dmcp.core.httpToken` (`isNonLoopback` treats wildcards and unresolvable hosts
  as exposed = fail-safe).
- Regression tests: `HttpFacadeTest` (401 on every route without/with-wrong token,
  200 with correct token, open on loopback), `McpCoreBindGuardTest` (bind
  classification), `PolicySideTableDriftTest` (any L3/L4 dangerous built-in must
  declare a ring or it falls back to R3 — latent under-gating guard).

### Related files
- `core/.../http/HttpFacade.java` (authToken field, `authorized()`, 401 path)
- `core/.../McpCore.java` (`isNonLoopback`, non-loopback refusal at facade start)
- `.ai-notes/SECURITY.md` 威胁模型 (the invariant this enforces)

---

## KI-3 — L6 object-handle gating was "voluntary" — bypassable by omitting the handle (FIXED)

**Status:** RESOLVED (fixed 2026-07-11; see `../architecture/adr/ADR-0001-l6-strict-handle-posture.md`).
**Severity (before fix):** medium — a security-layer that a caller could opt out of.

A four-dimension audit (this session) found L6's gate seam
`ObManager.checkRequest` returned `allowed()` whenever a request carried **no
`handle` arg**. The 6 handle-op debug tools (`HANDLE_OPS`:
`debug_read_local`/`_write_local`/`_force_return`/`_suspend_thread`/`_pop_frame`/`_single_step`)
fall back to name-based resolution (`findThread(name)`) when no handle is given — so
**omitting the handle bypassed L6 entirely**, and L6 exists precisely to close that
jthread name-reuse TOCTOU via a frozen handle. Confirmed at source level with
codegraph. For a project that advertises a "7-layer security kernel", a layer the
caller could silently skip was a real (if posture-dependent) gap.

### Fix
- `ObManager` gained an additive `strictHandles` posture (default false = historical
  voluntary behavior). When true, a `HANDLE_OPS` tool invoked without a `handle` is
  **denied** rather than falling through to the name path.
- `McpCore.buildObjectManager` wires `strictHandles=true` under
  `-Dmcp.core.hardened=true`. Default (dev) posture and the whole headless suite are
  unchanged.
- Regression test: `L6ObjectHandleTest.strictHandlePostureDeniesHandleLessHandleOp`
  (teeth-verified — fails on the pre-fix code). core suite green (count per STATUS.md).

### Note on posture
This is opt-in (hardened). The shipped default remains wide-open dev posture
(R-1, loopback, no auth) by design — see SECURITY.md 威胁模型. The fix removes the
bypass *when L6 is meant to bite*, not by default.

### Related files
- `core/.../ob/ObManager.java` (`strictHandles`, `checkRequest` strict branch)
- `core/.../McpCore.java` (`buildObjectManager` hardened→strictHandles wiring)
- `../architecture/adr/ADR-0001-l6-strict-handle-posture.md` (the decision record)

---

## KI-4 — Singleplayer world entry crashed: LocalServerChannel on a Nio group (RESOLVED — signed compat patch, live-verified)

**Status:** RESOLVED — compat patch **MCP-KI0004** IMPLEMENTED + SIGNED + ARMS + **LIVE-VERIFIED 2026-07-14** (commits `58fd15b` ASM transform + `ccb79dd` kernel-key signing). `Ki4LocalServerChannelPatch` rewrites `NetworkSystem.addLocalEndpoint`'s `GETSTATIC eventLoops` (NioEventLoopGroup) → `SERVER_LOCAL_EVENTLOOP` (DefaultEventLoopGroup) as a load-time ASM transform — `client/` stays pure vanilla (its `addLocalEndpoint` is still the buggy Nio-group bind; the patch fixes it at class-load, not in source). It carries a real Ed25519 signature that verifies against the baked kernel public key, so it ARMS on the default premain path (proven headless by `Ki4SignedArmingTest`; empty TrustAnchors still arms nothing = fail-safe intact). Kill switch: `-Dmcp.compat.ki4=false`.
**Live verify PASSED (owner, 2026-07-14):** MCP `gui_click` drove the real client main-menu → Singleplayer → Create World → Create; `read_player_state` went from "not in world" → `Player0 pos=(721.5,64,13.5) hp=20`, with no `hs_err` crash dump and no `LocalServerChannel` error. The patch arms and takes effect on a real machine under Netty 4.2. Recorded as PHASE 0 / P0.5 **PASS** in `../design-briefs/perception-control/PROGRESS.md`.
**History:** OPEN → FIX-APPLIED-in-client (2026-07-11) → reverted to keep client vanilla → re-implemented as signed compat patch (2026-07-14, arms headless) → live-verified in-world (2026-07-14) = RESOLVED.
**Severity (before fix):** high for singleplayer — integrated server could not start,
so NO singleplayer world could be entered (multiplayer/local-menu unaffected).

Driving the real GUI via MCP (Singleplayer → Create New World → Create) crashed in
`Minecraft.launchIntegratedServer` with **"IoHandle of type
LocalServerChannel$LocalServerUnsafe not supported"**. Root cause: the Netty 4.2
upgrade changed the transport model to per-group `IoHandler`s. A `LocalServerChannel`
needs a `LocalIoHandler`-backed group (`DefaultEventLoopGroup`), but
`NetworkSystem.addLocalEndpoint()` bound it on `eventLoops` — a **`NioEventLoopGroup`**
(NioIoHandler) — which can't drive a local channel. (The client side,
`NetworkManager.provideLocalClient`, was already correct — it used
`CLIENT_LOCAL_EVENTLOOP`, a DefaultEventLoopGroup. Only the server side was wrong.)

### Fix (later REVERTED — see status)
> Applied to `client/` source and live-proven, then **reverted** to keep `client/` pure vanilla. Records what the fix WAS; no longer in the tree. Re-implementation lands as signed compat patch MCP-KI0004.
- `NetworkSystem.addLocalEndpoint()` now binds the `LocalServerChannel` on the
  pre-existing `SERVER_LOCAL_EVENTLOOP` (a `DefaultEventLoopGroup`) instead of the Nio
  `eventLoops`. One-line change; the correct group already existed but was unused.
- Regression: `client/.../LocalServerChannelBindTest` (3) — proves LocalServerChannel
  binds on a DefaultEventLoopGroup and FAILS on a Nio group with the exact IoHandle
  signature (reproduces the bug), so the fix stays load-bearing. (client was 20/20 green at the time; this test was reverted with the fix — client is now 14, pure vanilla.)
- **Live-verified:** after the fix, GUI-drove into a generated Roofed Forest world
  (`read_player_state` → real pos/health), then `capture_screen` returned a real
  in-world frame. This is also where KI-1's in-world visual check became possible.

### Related files
- `client/.../network/NetworkSystem.java` (`addLocalEndpoint` group; `SERVER_LOCAL_EVENTLOOP`)
- `client/.../network/NetworkManager.java` (`CLIENT_LOCAL_EVENTLOOP` — the already-correct client side)

---

## KI-5 — PatchGuard (pg) was advertised-but-dead: never applied, zero @Guarded consumers (RESOLVED — first real consumer wired)

**Status:** RESOLVED (2026-07-14, commit `0a65be8`). pg now hardens a real product class:
`board`'s `TickCounterChip` carries `@Guarded` (`import net.marcloud.pg.Guarded`), and
`board/pom.xml` binds `pg-maven-plugin` at `process-classes` (deterministic `seed=1337`) —
the first module to wire it. Teeth: `TickCounterChipHardeningTest` asserts the packaged
`.class` no longer contains the plaintext strings and still loads (a `pg$dec` decoder is
injected). This depended on KI-6/KI-7 being fixed first (they were, `6f95640`), since the
engine had correctness bugs that would have broken the build once wired. pg is no longer
advertised-but-dead. Second-patch issuance flow (signing another consumer) is tracked as
PROGRESS.md C.6, still to do.
**Severity (before fix):** honesty / dead-code — nothing in the product was actually hardened.

**Original defect (2026-07-13 full-project review):** `pg/pom.xml` and `pg-maven-plugin`
advertised that `@Guarded` classes get build-time bytecode hardening, but `@Guarded` had
ZERO consumers outside `pg/` itself and the plugin was wired into no module's build
(`git grep '@Guarded' -- '*.java'` and `git grep 'pg-maven-plugin' -- '*.xml'` both
returned only pg's own files). So the hardening pass never ran — any claim that pg
protected kernel/security classes was false, a north-star violation ("no advertised-but-dead
tools"). See `../design-briefs/pg-hardening-lib-design.md` (status banner).

---

## KI-6 — pg StringConstantPass is non-idempotent: a second run emits an unloadable class reported as HARDENED

**Status:** RESOLVED (fixed 2026-07-13, commit `6f95640`). `StringConstantPass.apply()` now scans `cn.methods` for an existing `pg$dec` (DECODE_NAME/DECODE_DESC) and returns the class bytes unchanged if present (`StringConstantPass.java:~67`), so a second `process-classes`/package without a `clean` no longer injects a duplicate decoder. Teeth test in the pg-engine suite (idempotent re-harden stays loadable) fails on pre-fix code.
**Severity (before fix):** correctness — broke the fail-safe contract.

`StringConstantPass.apply()` unconditionally injects a `pg$dec` decoder method and
re-encodes any String LDC it sees, with no guard against a prior run. A second
`process-classes`/package without an intervening `clean` (normal incremental build:
already-hardened classes sit in `target/classes` and get hardened again) injects a
duplicate `pg$dec` → `ClassFormatError` at load time. `HardenEngine.verify()` does
NOT catch this, so the "fail-safe" engine emits an unloadable class it reports as
HARDENED.

**Fix:** make the pass idempotent — before encoding, scan `cn.methods` for an
existing `pg$dec` (DECODE_DESC) and return the class bytes unchanged if present.
(`pg/pg-engine/.../pass/StringConstantPass.java:96`)

---

## KI-7 — pg ClassWriter.COMPUTE_FRAMES resolves reference-type merges on the plugin classloader → guarded methods silently fail to harden

**Status:** RESOLVED (fixed 2026-07-13, commit `6f95640`). A new `FrameSafeClassWriter` (`pg/pg-engine/.../FrameSafeClassWriter.java`) overrides `getCommonSuperClass` to return `java/lang/Object`, used in both `StringConstantPass.apply` and `HardenEngine.verify()`; `verify()` also switched `CheckClassAdapter` to structural-only (`checkDataFlow=false`) to avoid the second `Class.forName` path via `SimpleVerifier`. Teeth test (a reference-type frame-merge class hardens instead of reverting) fails on pre-fix code.
**Severity (before fix):** api-misuse — silent failure, plaintext strings shipped anyway.

`StringConstantPass` (`:98`) and `HardenEngine.verify()` (`:118-123`) use
`ClassWriter.COMPUTE_FRAMES`, whose default `getCommonSuperClass` resolves both types
via `Class.forName` on the writer's (Maven plugin realm) classloader — which cannot
see the project classes being hardened. Any `@Guarded` method with a reference-type
stack-map frame merge (if/else, try/catch, loop assigning different object types)
throws, the engine reverts to the original, and the class ships with plaintext
strings intact — while the build reports success. The teeth tests never exercise this
(they use simple methods with no reference-type merge).

**Fix:** override `ClassWriter.getCommonSuperClass` to default to `java/lang/Object`
(or resolve against a classloader that includes the project's compile classpath).

---

## KI-8 — Enabling P-SECURE (L1) silently drops the L6 object-handle gate

**Status:** RESOLVED (fixed 2026-07-13, commit `d8e3040`). `McpCore.buildEngine` now returns `new SeHandleGatedMonitor(remote, objects)` in the P-SECURE branch when `objects != null` (`McpCore.java:~357`), splicing a LOCAL L6 handle gate in front of the remote authority instead of dropping the `ObManager`. `SeHandleGatedMonitor` (`core/.../se/SeHandleGatedMonitor.java`) asks the remote authority for L1-L5 first (fail-closed), then runs the local L6 `ObManager.checkRequest` last (mirrors `SeLocalMonitor` L6-last ordering); everything else delegates across the wall. Regression tests `McpCorePsecureL6WiringTest` + `PSecureHandleGateTest` (both added by `d8e3040`); reverting `buildEngine` to the bare remote fails the wiring test.
**Severity (before fix):** security-bypass — only bit when the hardened opt-in stack (psecure + handles + hardened) was enabled together.

`McpCore.buildEngine` (`:347`) returns `new SeRemoteMonitor(host, port, token, 2000)`
in the P-SECURE branch and DROPS the `objects` (ObManager) that every other branch
passes to `SeLocalMonitor`. `SeRemoteMonitor.evaluate()` sends only tool name + builtIn
over the wire (no args), so with `-Dmcp.core.psecure=true -Dmcp.core.handles=true
-Dmcp.core.hardened=true` all enabled together, L6 + its strictHandles TOCTOU
protection silently do nothing — the operator "hardening" the deployment actually
removes a layer.

**Fix direction (needs the two processes running to verify):** either forward the
handle key across the ALPC wire and give the P-SECURE authority its own ObManager, or
refuse to enable L6 together with P-SECURE (fail-closed with a clear message).
Headless tests can't prove the cross-process decision carries the handle — defer to a
live P-SECURE run.

---

## KI-9 — seam_netty_install inbound observation is dead on the real MC pipeline

**Status:** RESOLVED — FIXED + LIVE-VERIFIED 2026-07-15 (= PHASE P.1, commit `22134fb`). Live: after `seam_netty_install`, `packets_tail{dir:IN}` returned count=30 real inbound server packets (S17PacketEntityLookMove / S19PacketEntityHeadLook / …) with tickId stamps — inbound observation fires on the real pipeline (was 0 before the fix). Outbound C04PacketPlayerPosition also observed with a working summary. `NettyTap.installBuiltinTap`/`installBefore`/`resolveTerminalName` now insert the tap BEFORE the terminal `packet_handler` (addBefore; resolve by handler identity → "packet_handler" → last SimpleChannelInboundHandler → addLast fallback); `SeamController.installNettyTap` uses it. Headless teeth `NettyTapInboundBeforeTerminalTest` (addLast=0 inbound / installBefore=1). Real-pipeline inbound firing still needs a live server connection (`SeamOnLiveConnectionLiveIT`, `-Dmcp.it.live=true`; confirm S00PacketKeepAlive inbound via `packets_tail {dir:IN, includeNoise:true}`). From the 2026-07-13 review.
**Severity:** honesty — outbound works, inbound silently never fires.

`NettyTap` installs its tap with `p.addLast(name, handler)` (`NettyTap.java:95`), but
vanilla `NetworkManager extends SimpleChannelInboundHandler<Packet>` sits at the tail
and CONSUMES inbound packets before a later-added handler sees them. So on a live
server connection `seam_netty_install` reports success and advertises
`SeamPacketInboundEvent`, but zero inbound events ever fire (outbound works — the tail
sees outbound writes first). The isolated `EmbeddedChannel` unit test passes because
it lacks the real terminal handler.

**Fix direction (needs a live server to verify):** install inbound BEFORE the terminal
`packet_handler` (`pipeline.addBefore("packet_handler", name, handler)` or after the
decoder). A headless test can't reproduce the real pipeline ordering — verify against a
live connection that inbound events actually fire.

---

## KI-10 — compat signature binds a manifest label, not the executed transform code

**Status:** MITIGATED (TUF L0, commit `d77f832`) / residual defer. L0 adds a second independent gate: `ContentHash` recomputes `sha256(transform(canary))` and compares it to the patch's `expectedCanaryHash()`, so an accidental transform change fails verification. **Honest boundary (S3 red-team 2026-07-15):** L0 is NOT signed — both `transform()` and the sibling `EXPECTED_CANARY_HASH` constant live in the same patch class, so an attacker with classpath/code-exec can edit both and defeat L0; it is drift-detection, not adversarial binding. The Ed25519 signature still binds only a stable manifest LABEL (targetClass/contentHash-of-seed/keyId/status/kiRef/publisher/version), NOT the executed transform bytes. Full length-prefixed-payload binding for the data-channel-delivery endgame remains deferred to dwgx. Latent (NOT arm-exploitable) under in-code registration. From the 2026-07-13 adversarial red-team. From the 2026-07-13
adversarial red-team of the Ed25519 Phase-A signer (blue-team confirmed, 1 of 5 findings
survived; the other 4 were fail-closed / working-as-designed / already blocked by session
binding + dual-use keyId + empty-anchors lookup).
**Severity:** latent design + doc defect; HIGH at the scheme's data-delivery endgame.

The Ed25519 patch signature (`PatchCanonicalizer.signingInput`) covers only
`(targetClass, contentHash, keyId)`. `contentHash` is an author-supplied string stored
verbatim by `PatchManifest.withTransform` — nothing recomputes it from the actual bytes
`CompatPatch.transform()` emits. Proof of the decoupling: `IdentityProbePatch` declares
`contentHash = sha256Hex("identity-no-op")` while its `transform()` returns null. So the
signature authenticates a manifest LABEL, not the executable transform.

**Why not exploitable today:** patches are registered in-code (classpath `CompatPatch`
objects via `CompatDatabase.register`). An attacker who can supply a malicious
`transform()` body already has core code-execution, so the signature adds nothing to
defend against them. Shipped default is inert anyway (empty DB + empty `TrustAnchors`).

**Why it must be fixed before data delivery:** the documented endgame (compat-TUF) is
data-channel patch delivery. The instant a manifest+transform arrives as DATA,
`verify()` passes on the triple while the transform payload is unbound → a malicious
transform runs. The scheme's central claim ("you cannot load a transform the authority
did not bless") does not hold.

**Fix direction:** bind the actual executable content into the signature — compute
`contentHash = sha256(canonical transform/payload bytes)` at sign time and re-derive +
compare from the delivered payload at load time (reject on mismatch), or sign over the
length-prefixed transform payload directly. This is a security-semantic change to
`withTransform` / the signing input — needs dwgx sign-off (do NOT invent the binding
unasked). Until then, `PatchCanonicalizer` javadoc has been corrected to stop claiming
`contentHash` binds transform bytes and to state integrity rests on in-code registration.
