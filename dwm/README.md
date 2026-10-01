# dwm — UI subsystem

**Status: implemented. Substrate is qml4j.** A QML scene graph rendered by Skija into
Minecraft's own framebuffer, surfaced as a real `GuiScreen`. qml4j is a published Maven
Central artifact (`io.github.timer-err:qml4j-core`); the pin is one property in
`dwm/pom.xml`. It is **never vendored**. Following an upstream release is that one line.
Changes needed inside qml4j go through the `dwgx/qml4j` topic stack and up to `TIMER-err`.

Live: macOS Apple GL 2.1; Windows NVIDIA / GLFW 3.3 compat. Overlay on qml4j **0.2.27**
opened `QmlGuiScreen` over the world (eval_java, REST `/v1/screen`, jar
`qml4j-core-0.2.27`). See `docs/macos/dwm-qml4j-plan.md` for the original rollout and
`docs/dwm/dwm-architecture-comparison.md` for the NT DWM metaphor.

## What it is

DWM is codenamed after Windows' **Desktop Window Manager**, following the project's
NT/hardware metaphor:

| Codename | Subsystem |
|---|---|
| core | NT kernel (7-layer privilege, capabilities) |
| board | PCB (feature chips on a backplane) |
| compat | AppCompat (signed game-porting patches) |
| **dwm** | **Desktop Window Manager (the UI layer)** |

dwm is a **detachable AUXILIARY**. Zero security-decision power. Deleting the module
leaves `core` / `board` / `client` compiling. That is ADR-0002, not a style preference.

## Substrate

qml4j is the UI engine. Not a plugin, not one of several hot-swappable renderers.

| Fact | Consequence |
|---|---|
| qml4j creates no window and calls no GLFW | It can run inside MC's GL context |
| Skija is `provided` in qml4j-core | The host ships natives; one artifact carries `skija-windows-x64`, `skija-macos-arm64` and `skija-linux-x64` so the loader picks by `os.name`. Linux is there for CI: ubuntu-latest is where the unit tests run, and Skija's loader fails the run outright rather than skipping when its native is absent |
| qml4j's stock `GlfwSurfaceBackend` owns a window | Unusable here. macOS `-XstartOnFirstThread` already gave the process main thread to GLFW; AppKit will not run a second window loop. We implement qml4j's `SurfaceBackend` as `McpFboSurfaceBackend` and wrap MC's FBO |
| Native / qml4j / Skija types | Confined to `net.marcloud.mcp.dwm.qml`. Everywhere else talks to the `ui` SPI in JDK types |

`UiSurface` / `UiInput` / `UiKeys` / `UiWindowHost` are a **type firewall**, not a
hot-swap bus. They exist so Board, QML scenes, and tests can compile when qml4j is
absent, and so a qml4j type cannot leak into the rest of the module. They are not an
invitation to reintroduce `dwm-gl` / imgui / skiko.

## Stack

```
Minecraft (vanilla GuiScreen lifecycle)
  KI-11 compat patch  ->  Minecraft.dispatchKeypresses()
                          INVOKESTATIC DwmHotkey.onKeyEvent
                          default scancode: RSHIFT  (-Dmcp.dwm.hotkey / -Dmcp.core.overlay=true)
    DwmEntry.createScreen()                 JDK types only; names QmlGuiScreen as a String
      QmlGuiScreen extends GuiScreen        owns show/hide, resize, input, focus, pause=false
        QmlUiSurface                        UiSurface + UiInput
          QmlEngine + QmlView               qml4j-core
            context "Dwm"        -> DwmContext  -> LiveState (reflective Backplane)
            context "WindowHost" -> WindowCommands -> UiWindowHost
            clipboard            -> GlfwClipboard
          McpFboSurfaceBackend              qml4j SurfaceBackend
            Skija DirectContext wrap of MC FBO
            RedirectionSurface              offscreen layer, blit each frame
          GlStateGuard.enter / leave        around open, every frame, and close
```

A frame:

1. `QmlGuiScreen.drawScreen` reads MC's framebuffer id and size.
2. `GlStateGuard.enter` snapshots real GL + `GlStateManager` shadows (alpha test off for
   Skia, sampler objects via `ARBSamplerObjects`, ARRAY_BUFFER, attrib arrays, FBO, program).
   The same bracket wraps the two other places Skija issues raw GL — `QmlUiSurface.open`
   (`DirectContext.makeGL` + the scene load, re-entered by MC on every resize) and
   `closeQuietly` (`view.dispose` + `context.close`) — because a pair that covered only the
   frame would leave those paths leaking exactly the state the guard exists to restore.
3. `McpFboSurfaceBackend.frameTarget` rebuilds the Skia wrap if size or FBO id moved.
4. `QmlView.renderFrame` ticks the retained Item tree and paints into the offscreen layer.
5. The layer is composited onto MC's framebuffer. MC redraws the world every frame, so a
   skipped composite means the menu vanishes — and it CAN be skipped: with no live Skia
   wrap for MC's framebuffer the backend has nothing to paint into and the frame is
   dropped. That state is neither silent nor permanent: `QmlUiSurface` records the
   backend's own reason in `lastError()`, logs it once, and answers `isOpen()` false until
   a wrap takes, while the backend retries a failed wrap on a cooldown (60 frames) instead
   of thrashing a Skia context per frame or giving up for good. So the composite is never
   skipped *silently* — which is the claim the earlier wording overstated. Scene-repaint
   skipping was withdrawn — `renderFrame` ticks animations inside itself, so skipping on a
   change counter froze every animation on its first frame.
6. `GlStateGuard.leave` restores GL and rewrites MC's shadow. Unconditional, even on fault.

Input: `QmlGuiScreen` overrides `handleMouseInput` so coordinates stay framebuffer pixels
with a top-left origin (Y flip only; the shim already applied the Retina factor). Keys
go through `UiKeys` then `QmlKeyMap`. Buttons are LWJGL2 zero-based indices at the SPI
and Qt bitmasks inside the adapter (`QmlButtonMap`).

Live data: `LiveState` reflects into Board's Backplane (`kernel.state`, `chip.roster`,
`chip.toggle`). Absence is normal. The only write dwm can perform is toggling a chip by
id; board marshals that onto the game thread. dwm imports no `core` class
(`DwmEntryTest.noSourceImportsCore`).

## Naming a control the agent can reach

`gui_snapshot` reflects `GuiScreen.buttonList`, and `QmlElementBridge` puts one invisible
`QmlProxyButton` per interactive QML node into it, so the panel is drivable by the same
tool calls as a chest GUI. The id it publishes is the `objectName` on the node itself or
on its nearest named ancestor — so a name belongs on whatever a reader would call the
control, and a hit area inside a named wrapper inherits it.

Two rules, both learned on a live client:

- **Every published control needs a name, and the name must be unique in the panel.** A
  node with no name anywhere above it is published as its child-index path
  (`/0/5/0/3/0/1/0/2/1/1/4`), which identifies nothing and re-points at a different control
  after a `Loader` swap. A name two controls claim is not a name: the table discards it,
  publishes every holder under its path, and logs one line to stderr. An earlier version
  appended the coordinates instead — a place rather than a name, and a place that moves
  when vanilla re-lays the screen out on a GUI-scale change.
- **A component holding more than one hit area has to name its own parts.** A
  `FluentSettingsExpander` contains three, and left anonymous all three resolve to the
  expander's single name; that is why it derives `…Header` and `…Chevron` from the
  instance's own `objectName` rather than taking a literal that would collide across
  instances.

`QmlElementNamesAreReadableTest` loads every shipped document and checks the published
ids, so the rule holds on any build. `ShippedShellNamesEveryControlIT` repeats it on the
composed shell, which needs a live window: a page's controls do not exist until its rail
row has been clicked, and the click takes effect inside a render frame.

## Packages

| Package | Holds |
|---|---|
| `net.marcloud.mcp.dwm` | `DwmEntry` — the class Board looks for. Names the adapter as a string, so this class links without qml4j or Skija. |
| `net.marcloud.mcp.dwm.ui` | SPI (`UiSurface`, `UiInput`, `UiKeys`, `UiWindowHost`) and `LiveState`. Plain JVM types only. |
| `net.marcloud.mcp.dwm.qml` | The one adapter package: every Skija and qml4j type lives here. |

Scenes live under `src/main/resources/dwm/`. Default is `dwm/Shell.qml` (Fluent window +
navigation pages). `dwm/Main.qml` is still shipped: a single-panel menu, the wrong shape
for settings.

Coordinates crossing into `qml` are framebuffer pixels, top-left origin. The shim already
scales GLFW window units by the Retina factor and `mc.displayWidth` comes from
`Display.getWidth()`, so both are already in that space — only the Y flip is needed, and
any further DPI scaling would double it. The canvas transform carries `Display.getContentScaleX()`;
the scene root is sized in logical units.

## The contract

1. A **detachable auxiliary**: zero security-decision power, imports no `core` class.
2. Discovered by **string + reflection**. `DwmEntry` is the peer Board can look up.
   Deleting dwm must leave everything else compiling. A missing qml4j at runtime is a
   normal condition (`isAvailable()` is false, `createScreen()` returns null, never throws).
3. **qml4j is the substrate.** Native types live in exactly one adapter package. The `ui`
   SPI is the type firewall, not a second renderer marketplace.
4. The menu is a real **`GuiScreen`**. MC's screen lifecycle owns render, input, resize
   and focus. Not a bytecode-injected parallel pipeline, not a second window.
5. **Nothing may escape into the game loop.** A fault records, the surface goes inert,
   the client keeps running.
6. **Do not vendor qml4j.** Pin, test, follow. If the engine itself must change, PR
   upstream; forking it into this tree ends the ability to track releases.

Opening the screen at runtime is **KI-11** (`Ki11DwmHotkeyPatch`): a signed compat
patch injects one `INVOKESTATIC` at the entry of `Minecraft.dispatchKeypresses()`.
client/ stays vanilla. The hook is inert unless `-Dmcp.dwm.hotkey` or
`-Dmcp.core.overlay=true` is set.

## History

Every prior UI implementation was removed by owner decision:

- the DWM MD3 component tree + theme,
- the render backends `dwm-gl` (pure OpenGL), `dwm-skiko` (Skia), `dwm-imgui` (Dear ImGui),
- the qml4j always-on desktop shell (taskbar, independent windows).

They are **gone from this repository**, and the two branches that used to be named here —
`backup/qml4j-desktop` (the qml4j/Skija desktop) and `backup/overlay-guiscreen` (the earlier
single-GuiScreen overlay) — were never pushed anywhere either: `git branch -a` lists no such
refs, locally or on any remote. Treat the deletion as final rather than as something to be
recovered from this tree.

Do not revive them as parallel backends. The desktop shell in particular needs a process
main thread it does not have on macOS.

The `dwm/README.md` that still talked about a hot-swappable `RenderBackend` / `DrawContext`
SPI described that older stack. This file matches the code.
