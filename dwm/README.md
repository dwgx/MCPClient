# dwm — UI subsystem (concept placeholder)

**Status: intentionally empty.** This module holds no code today. It is the reserved
seat of the in-game UI layer, kept in the reactor as a concept anchor.

## What it is

DWM is codenamed after Windows' **Desktop Window Manager**, following the project's
NT/hardware metaphor:

| Codename | Subsystem |
|---|---|
| core | NT kernel (7-layer privilege, capabilities) |
| board | PCB (feature chips on a backplane) |
| compat | AppCompat (signed game-porting patches) |
| **dwm** | **Desktop Window Manager (the UI layer)** |

## Why it is empty

Every prior UI implementation was removed by owner decision:

- the DWM MD3 component tree + theme,
- the render backends `dwm-gl` (pure OpenGL), `dwm-skiko` (Skia), `dwm-imgui` (Dear ImGui),
- the `qml4j` (Skija/QML) always-on desktop shell.

They are **recoverable in git**:

- `backup/qml4j-desktop` — the qml4j/Skija desktop (taskbar, windows, live System Info panel)
- `backup/overlay-guiscreen` — the earlier single-GuiScreen overlay

## The contract, if it is ever re-implemented

- A **detachable auxiliary** layer: zero security-decision power, imports no `core` class.
- Discovered/loaded by **Board via the reflective Backplane** (the same idiom Board uses
  to find `mcp-core`). Deleting dwm must leave everything else compiling.
- Rendering sits behind a `RenderBackend` / `DrawContext` SPI so the backend
  (OpenGL / imgui / Skia / …) is hot-swappable and its native types live in exactly one
  adapter package.
- The menu itself should be a real `GuiScreen` (MC's screen lifecycle owns render, input,
  resize, focus) — the reference-client pattern — not a bytecode-injected parallel pipeline.
