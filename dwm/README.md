# dwm — UI subsystem

**Status: implemented on qml4j.** A QML scene graph rendered by Skija into Minecraft's own
framebuffer, surfaced as a real `GuiScreen`. Verified live on macOS 26.5 / M2 / Temurin 25
(Apple's GL 2.1 compatibility context): scene opens, renders, survives a resize, routes
input, exits clean. See `docs/macos/dwm-qml4j-plan.md` for the rollout and
`QmlPipelineLiveIT` for the guard.

qml4j comes from Maven Central (`io.github.timer-err:qml4j-core`, version property in
`dwm/pom.xml`) and is **never vendored** — following an upstream release is one line.
Changes needed inside qml4j go through the `dwgx/qml4j` topic stack and up to `TIMER-err`.

## What it is

DWM is codenamed after Windows' **Desktop Window Manager**, following the project's
NT/hardware metaphor:

| Codename | Subsystem |
|---|---|
| core | NT kernel (7-layer privilege, capabilities) |
| board | PCB (feature chips on a backplane) |
| compat | AppCompat (signed game-porting patches) |
| **dwm** | **Desktop Window Manager (the UI layer)** |

## Structure

| Package | Holds |
|---|---|
| `net.marcloud.mcp.dwm` | `DwmEntry` — what Board finds. Names the backend by string, so it links without qml4j present. |
| `net.marcloud.mcp.dwm.ui` | The SPI (`UiSurface`, `UiInput`, `UiKeys`). Plain JVM types only. |
| `net.marcloud.mcp.dwm.qml` | The one adapter package: every Skija and qml4j type lives here. |

Coordinates crossing into `qml` are framebuffer pixels, top-left origin. The shim already
scales GLFW's window units by the Retina factor and `mc.displayWidth` comes from
`Display.getWidth()`, so both are already in that space — only the Y flip is needed, and any
further DPI scaling would double it.

## History

Every prior UI implementation was removed by owner decision:

- the DWM MD3 component tree + theme,
- the render backends `dwm-gl` (pure OpenGL), `dwm-skiko` (Skia), `dwm-imgui` (Dear ImGui),
- the `qml4j` (Skija/QML) always-on desktop shell.

They are **recoverable in git**:

- `backup/qml4j-desktop` — the qml4j/Skija desktop (taskbar, windows, live System Info panel)
- `backup/overlay-guiscreen` — the earlier single-GuiScreen overlay

## The contract

- A **detachable auxiliary** layer: zero security-decision power, imports no `core` class.
- Discovered/loaded by **Board via the reflective Backplane** (the same idiom Board uses
  to find `mcp-core`). Deleting dwm must leave everything else compiling.
- Rendering sits behind a `RenderBackend` / `DrawContext` SPI so the backend
  (OpenGL / imgui / Skia / …) is hot-swappable and its native types live in exactly one
  adapter package.
- The menu itself should be a real `GuiScreen` (MC's screen lifecycle owns render, input,
  resize, focus) — the reference-client pattern — not a bytecode-injected parallel pipeline.
