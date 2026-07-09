# Known Issues

Tracked defects that are non-blocking and deferred. Each entry records what was
verified, ruled out, and the most promising fix direction — so a future session
can resume without re-investigating from scratch.

---

## KI-1 — Mipmap blue speckles on grass/foliage + distant block seams

**Status:** OPEN (non-blocking — workaround: set Video Settings → Mipmap Levels = 0)
**Severity:** cosmetic (distant terrain), does not affect gameplay, protocol, or single/multiplayer.
**First seen:** in-world on a real display (superflat and normal worlds). Blue
(sky-colored) speckles scattered on grass, worsening with distance; colored
seams between distant blocks. Only visible on a real GPU/display — not
reproducible headless.

### Confirmed by investigation
- **It IS the mipmap path.** Setting Mipmap Levels = 0 in Video Settings makes the
  speckles disappear entirely (user-verified). So the fault is in mipmap
  generation, upload, or how LWJGL3 samples the generated mip levels.
- **The Java algorithm is byte-for-byte identical to vanilla 1.8.9.**
  `TextureUtil.generateMipmapData` and `blendColors` (the alpha-weighted,
  gamma-correct 2x2 downscale with the `/4.0F` divisor and `alpha<96 -> 0` cutoff)
  match the untouched base (`_analyze_base`) exactly. This is Mojang's original
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
