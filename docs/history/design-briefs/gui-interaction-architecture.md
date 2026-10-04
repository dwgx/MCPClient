---
doc: brief-gui
title: Structured GUI Interaction for MCPClient — Architecture
layer: archive
status: archived
updated: 2026-07-11
parent: ../README.md
next:
  - path: ../architecture/02-CAPABILITIES.md
    when: 需要 GUI 交互能力的当前权威状态
read_if: 历史设计 brief(已实现);仅当你在改结构化 GUI 交互(gui_snapshot/click/type)或做 Phase 5/6 时想看原始设计时才读。
---
> **归档设计 brief**(已实现)— 本文的结构化 GUI 交互设计已落地(提交 b1d9cba):`gui_snapshot` + `gui_click_element`/`gui_type_text`/`gui_press_key`,`core/.../gui/` 包,live 验证过。Phase 5(SoM 标注截图)/ Phase 6(操作轨迹)尚未做。当前状态见 `../architecture/02-CAPABILITIES.md`。

I have the ground truth I need. One finding is load-bearing and corrects a research brief, so I'll lead with it.

---

# Structured GUI Interaction for MCPClient — Architecture

## 0. Ground-truth correction (blocks the naive approach)

The `mc189-gui-internals` brief was written against `Southside-main/` (a **modern-mapped** fork: `x`/`y`, `xPos`/`yPos`, `getCount()`). **The actual target module is `client/`, and it uses vanilla 1.8.9 mappings.** I verified every field in `D:\Project\MCPClient\client\src\main\java\net\minecraft\...`:

| Element | Field/method (VANILLA — use these) | NOT (Southside) |
|---|---|---|
| `GuiButton` | `xPosition`,`yPosition` (public); `width`,`height` (**protected**); `displayString`,`id`,`enabled`,`visible` (public); `hovered` (protected) | ~~x,y~~ |
| `Slot` | `xDisplayPosition`,`yDisplayPosition`,`slotNumber` (public); `getStack()`,`getHasStack()` | ~~xPos,yPos~~ |
| `ItemStack` | `stackSize` (public); **`getStack()` returns `null` when empty — no `isEmpty()`** | ~~getCount()~~ |
| `GuiContainer` | `guiLeft`,`guiTop`,`xSize`,`ySize` (protected); `inventorySlots` (public `Container`) | — |
| `Container` | `inventorySlots` (public `List<Slot>`), `windowId` | — |
| `GuiTextField` | `xPosition`,`yPosition` (public); `width`,`height` (**private final**); `getText()`, `isFocused()`, `mouseClicked(x,y,btn)`, `textboxKeyTyped(char,int)`, `setText`, `setFocused` | — |
| `GuiScreen` | `width`,`height` (public, scaled-GUI space); `buttonList` (protected `List<GuiButton>`); `labelList`; `mouseClicked(int,int,int)` **protected throws IOException**; `keyTyped(char,int)` protected | — |
| `ScaledResolution` | `new ScaledResolution(Minecraft)` → `getScaleFactor()`, `getScaledWidth/Height()` | — |

Existing infra confirmed and reused as-is: `GameBridge.onGameThread(Callable, timeout)` (5s default), `ScreenCapture.capturePng/captureFrame` (already flips FBO to top-left origin, TYPE_INT_RGB), `DeepAccess` (`getField`/`invoke` with `AccessGate`+`RootResolver`), `SeamController` GLFW hooks (these are **observers** that publish events, not injectors), `Ring`/`ToolPolicy` gate (L2 ring / L3 write-integrity / L4 privilege / L5 caps).

---

## 1. Chosen architecture: hybrid, structured-first (ground-truth tree + SoM overlay + id-grounded actions)

**Decision.** Three coordinated layers:

1. **Ground-truth element tree** synthesized by reflecting the live `Minecraft.currentScreen` — the authoritative source, not vision-inferred.
2. **SoM-annotated PNG** (numbered boxes over buttons/slots/fields) generated in the *same* game-thread pass from the *same* tree, as visual confirmation.
3. **Action tools keyed by element id**, never pixels. The server resolves id → authoritative GUI coordinate → invokes the real `GuiScreen`/`GuiContainer`/`GuiTextField` handler on the game thread.

**Why this over the alternatives** (every brief converges here):

- **vs pure vision / Computer-Use / Operator-CUA** (`anthropic-computer-use`, `openai-uitars-grounding`): those regress pixels *because they lack a UI tree* and fight coordinate drift as their #1 failure mode. We *own* the tree (`buttonList`, `Slot` grid geometry). Adopting pixel-grounding would be throwing away ground truth to reintroduce the hardest, least-reliable step. UI-TARS/ShowUI still miss ~25% of grounding targets; a11y-tree stacks win on reliability, cost (~4K vs ~50K tokens), and latency.
- **vs OmniParser-style detection** (`openai-uitars-grounding`): OmniParser exists *because desktop apps hide their tree*. MC does not — we skip the lossy Florence-2/SAM detection entirely and get the top of SoM's accuracy curve (its own ablation: GT masks +14.5 mIoU) for free.
- **vs SoM overlay alone** (`som-overlay-grounding`): SoM's residual failure is ID↔region confusion in cluttered scenes (a dense 5×9 inventory is exactly that). Pairing the overlay with a parallel structured `{id,label,role,bbox}` list — and having the model return an *id* the server resolves server-side — eliminates that class: the image is confirmation, the JSON is truth.
- **vs CLI-Anything spirit** (`cli-anything-arch`): this *is* the "expose the real engine as an agent-native command surface, drive the real handlers, never reimplement" pattern — `gui_snapshot` is the mandatory `list/status`-before-acting introspection call.

The structured tree is the **action space**; the screenshot is **context/verification**. The LLM never touches a raw pixel, which is what kills both drift classes (see §5).

---

## 2. GUI-snapshot data model

Union of UIA/MSAA/AT-SPI mandatory core (`windows-uia-linux-at`: role/name/value/state/bounds/tree + typed capabilities) mapped onto `GuiScreen` reality. Emitted as JSON.

```jsonc
{
  "epoch": 42,                      // monotonic; increments on every currentScreen identity change (anti-stale guard, §5)
  "screen": "GuiInventory",         // currentScreen.getClass().getSimpleName(), or null
  "inWorld": true,
  "isContainer": true,              // instanceof GuiContainer
  "title": "Crafting",              // best-effort: labelList / container name
  "viewport": { "width": 427, "height": 240, "scaleFactor": 3,
                "framebufferWidth": 1281, "framebufferHeight": 720 },
  "elements": [
    {
      "id": "b0",                   // stable-within-epoch, kind-prefixed: b=button t=textfield s=slot
      "kind": "button",             // button | slot | textfield | label(non-interactable)
      "role": "PUSHBUTTON",         // UIA/MSAA control-type analogue
      "name": "Done",               // GuiButton.displayString / field text / item name
      "value": null,                // textfield text | slot stack "minecraft:diamond x3" | null
      "bounds": { "x": 178, "y": 200, "w": 98, "h": 20 },   // GUI (scaled) space, top-left origin
      "clickPoint": { "x": 227, "y": 210 },                 // authoritative center in GUI space
      "state": { "enabled": true, "visible": true, "focused": false, "hovered": false },
      "actions": ["invoke"],        // typed capabilities (UIA pattern idea)
      "attributes": {}              // free-form (AT-SPI attributes bag)
    },
    {
      "id": "s13", "kind": "slot", "role": "CELL",
      "name": "Diamond", "value": "minecraft:diamond x3",
      "bounds": { "x": 8, "y": 84, "w": 16, "h": 16 },
      "clickPoint": { "x": 16, "y": 92 },                   // xDisplayPosition+8 offset, +guiLeft/guiTop
      "state": { "enabled": true, "visible": true },
      "actions": ["left_click","right_click","shift_click"],
      "attributes": { "slotNumber": 13, "windowId": 0, "itemId": "minecraft:diamond",
                      "count": 3, "hasStack": true }
    },
    {
      "id": "t0", "kind": "textfield", "role": "EDIT",
      "name": null, "value": "search text",
      "bounds": { "x": 82, "y": 6, "w": 80, "h": 12 },
      "clickPoint": { "x": 122, "y": 12 },
      "state": { "enabled": true, "visible": true, "focused": true },
      "actions": ["click","set_text","type"],
      "attributes": {}
    }
  ]
}
```

Field notes tying model → MC reality:
- `bounds` is **GUI/scaled space** (what `mouseClicked` consumes) — this is deliberate so `clickPoint` feeds the handler with zero conversion (§5).
- **Slot** `clickPoint` = `(guiLeft + xDisplayPosition + 8, guiTop + yDisplayPosition + 8)`.
- `value` for a slot must **null-check** `getStack()` (1.8.9 uses `null`, not `isEmpty()`).
- `hovered` from `GuiButton.hovered` / `GuiContainer.hoveredSlot`.
- `label` elements come from `labelList` — included as non-interactable context, no actions.

---

## 3. New MCP tools (names, args, rings, internals mapping)

Registered in `ToolRegistry.all()`; rings added to `Ring.BUILTIN_RINGS`; policy rows added to `ToolPolicy.L3_WRITES`/`L4_PRIVILEGE`.

### Read tool — R2 OBSERVE (game-thread reads, like `capture_screen`/`scan_surroundings`)

**`gui_snapshot`** — the grounding call.
- Args: `{ includeImage?: bool=false, maxEdge?: int=1024, onlyInteractable?: bool=true }`
- Ring **R2**. No L3 write. L4: reuse `SE_SCREEN_CAP` when `includeImage=true` (it drives glReadPixels), else none.
- Internals: one `GameBridge.onGameThread` pass. Reflect `currentScreen`:
  - buttons ← iterate `buttonList` (protected; `DeepAccess.getField` or `setAccessible`), read `xPosition/yPosition/width/height/displayString/id/enabled/visible/hovered`.
  - textfields ← reflect **declared fields (walking superclasses) of type assignable to `GuiTextField`** on the concrete screen (they are NOT in any global list); read `getText()/isFocused()` + private `width/height` reflectively.
  - slots ← if `instanceof GuiContainer`: read `guiLeft/guiTop`, then `inventorySlots.inventorySlots` (`List<Slot>`); per slot read `slotNumber/xDisplayPosition/yDisplayPosition` + `getStack()` (null-safe).
  - labels ← `labelList`.
  - If `includeImage`, produce the annotated PNG in the same pass (§4).
- Returns JSON (+ optional `ImageContent`). Fails loudly (CLI-Anything principle): if `currentScreen==null` → `{screen:null, inWorld:...}` with a hint to use `scan_surroundings`.

### Action tools — R1 SYSTEM (outward effects: `windowClick` sends packets to the server; identical trust class to `send_chat`)

All three take `epoch` and reject on mismatch (§5), and take an `elementId` (never coordinates).

**`gui_click_element`**
- Args: `{ epoch: int, elementId: string, button?: "left"|"right"=left, clickType?: "click"|"shift"|"double"=click }`
- Ring **R1**; L3 = `HIGH` (mutates game/network state); L4 = new **`Privilege.SE_GUI_INTERACT`** (dedicated, clean gate — recommended over overloading `SE_NET_RAW`).
- Internals (game thread): resolve id in the current live tree; recompute `clickPoint` from live geometry (not the stale snapshot value); then:
  - button/textfield/generic → invoke `GuiScreen.mouseClicked(cx, cy, buttonCode)` reflectively (protected, `throws IOException`) → runs real `mousePressed`+`actionPerformed`.
  - slot → same `mouseClicked(cx,cy,btn)`; `GuiContainer` auto-resolves via `getSlotAtPosition`→`handleMouseClick`→`playerController.windowClick`. `shift` = button param that triggers quick-move.

**`gui_type_text`**
- Args: `{ epoch, elementId, text: string, clearFirst?: bool=false }`
- Ring **R1**, same policy row as above.
- Internals: if target textfield not focused, invoke its `mouseClicked` to focus; optional `setText("")`; then feed chars via `textboxKeyTyped(char, keyCode)` (or `setText` directly for speed). Verify via `getText()`.

**`gui_press_key`**
- Args: `{ epoch, key: string, elementId?: string }` (e.g. `"Return"`, `"Escape"`, `"E"`).
- Ring **R1**, same policy row.
- Internals: map key name → LWJGL keycode + char; invoke `GuiScreen.keyTyped(char, keyCode)`. Covers Esc-to-close, Enter-to-confirm, inventory-key toggles — keyboard nav is the brief's recommended path for fiddly widgets.

**Ring rationale (as requested):** reads sit at **R2** because they run on the game thread and can stall it (same as `capture_screen`). Clicks/type/keys are **outward effects** — `windowClick` emits `C0EPacketClickWindow` to the server and `actionPerformed` mutates shared state — so they belong at **R1** alongside `send_chat`/`send_raw_packet`, gated by a dedicated privilege and HIGH write-integrity. A lowered clearance then genuinely locks out interaction while still allowing observation. Human-in-the-loop confirmation (Anthropic's best prompt-injection mitigation) attaches naturally at R1.

---

## 4. Annotated screenshot generation & tree sync

**Single atomic game-thread pass** guarantees sync — the invariant that makes SoM safe here:

1. In one `GameBridge.onGameThread` callable: build the element tree from `currentScreen`, capture the FBO via the existing `ScreenCapture.captureFrame` (top-left origin, full res), draw marks, downscale, encode. Same `currentScreen` instance for tree + pixels ⇒ no divergence.
2. **Coordinate transform (GUI→framebuffer):** `ScreenCapture` already flips to top-left origin, and GUI space is top-left origin, so `fb_x = guiX * scaleFactor`, `fb_y = guiY * scaleFactor` (with `framebufferWidth ≈ scaledWidth * scaleFactor`). Draw boxes on the **full-res** BufferedImage, then downscale — marks scale with the image, so no post-scale offset. (If drawing after downscale instead, multiply by the same ratio `ScreenCapture.downscale` uses.)
3. **SoM placement rules** (`som-overlay-grounding`): outline-only boxes (light fill — high opacity hides the item and breaks recognition); number at each element's center; for the dense inventory grid use area-ascending + exclusion + offset-outside for collisions; **color-code box and its ID identically per element**. The number equals the JSON `id` (drop the kind prefix on the mark, keep it in the legend) so image and list share one symbol space.
4. Ship **both** the JSON legend and the PNG, instruction text *before* the image (Anthropic: improves accuracy), telling the model the PNG annotates the tree and it must answer with an `id`.

Anthropic tested that a bare coordinate *grid* doesn't help — this is not that. These are per-element speakable marks bound to ground-truth boxes, which is the technique that does help.

---

## 5. Agent loop & click-drift prevention

**Loop:** `gui_snapshot` (observe) → model picks `elementId` (decide) → `gui_click_element`/`gui_type_text`/`gui_press_key` with `epoch` (act) → `gui_snapshot` again + diff (verify). Prompt the model to re-snapshot and confirm the expected change before the next action (Anthropic: models otherwise assume success).

**Spatial drift — eliminated by construction.** The two traps (`som-overlay-grounding`, `anthropic-computer-use`): DPI/`ScaledResolution` mismatch and API image-downscale offset. Because the model returns an **id**, never a pixel, and the server resolves id → authoritative **GUI-space** `clickPoint` and feeds it straight to `mouseClicked` (which itself expects GUI-scaled coords), the whole framebuffer↔scaled↔window conversion chain — the documented source of "consistently offset" clicks — is never traversed for actions. The overlay's fb-pixel transform (§4) is used *only for drawing*, so an overlay bug can misplace a number but can never misplace a click.

**Temporal drift — guarded by epoch.** The one residual risk (measured ~6.5s observe→act gap; screen can change underneath). `GuiSnapshotService` holds a monotonic `epoch`, incremented whenever `currentScreen` identity changes (screen open/close/transition). Every action carries the `epoch` it was decided against. On the game thread, before acting, compare to the live epoch **and** a cheap structural fingerprint (screen class + buttonList size + slot count). Mismatch → reject with a loud, actionable error ("screen changed since epoch 42 — now GuiChest with 90 slots; call gui_snapshot again"), never a silent misclick. This is CLI-Anything's "fail loudly so the agent self-corrects" plus SoM's "re-ground before acting."

**Handler path over GLFW injection.** Use the deterministic path: invoke `mouseClicked/keyTyped` on the game thread. The `SeamController` GLFW hooks are observers (publish events), not injectors; synthetic `glfwSetCursorPos`+event-post is racy against the poll loop (the brief flags this). Reserve seam injection for a future hardware-fidelity mode.

---

## 6. Phased implementation plan

Each phase is independently verifiable; the security gate is exercised from phase 1.

**Phase 0 — Reflection accessors + null-safety (foundation).**
`GuiReflect` helper wrapping `DeepAccess` for the verified vanilla field set; unit-test against `client/` classes with a synthetic `GuiScreen` populated with buttons/labels. *Verify:* fields read without `NoSuchFieldException`; catches any future mapping drift immediately.

**Phase 1 — `gui_snapshot` (JSON only), R2, wired into gate.**
Reflect buttons/labels/textfields/slots on the game thread; register in `ToolRegistry`, ring in `BUILTIN_RINGS`. *Verify:* open real `GuiMainMenu` and `GuiInventory` in a running client; snapshot lists correct button labels and 45 inventory slots with coords; `mvn` build + a snapshot test; confirm R2 clearance gates it.

**Phase 2 — `GuiSnapshotService` + epoch/fingerprint.**
Centralize snapshot + epoch bookkeeping. *Verify:* epoch increments on `displayGuiScreen`; stale-epoch detection unit test.

**Phase 3 — `gui_click_element`, R1 + `SE_GUI_INTERACT`.**
Invoke `mouseClicked` on the game thread; epoch guard. *Verify:* click "Done"/"Quit" button on a menu changes `currentScreen` (observed via re-snapshot); click a chest slot moves an item (`getStack` before/after); confirm a lowered clearance blocks it and a stale epoch is rejected. Gate on human confirmation for irreversible clicks.

**Phase 4 — `gui_type_text` + `gui_press_key`, R1.**
*Verify:* type into a search/anvil/sign field → `getText()` matches; `Escape` closes a screen; `Return` confirms.

**Phase 5 — Annotated PNG (`includeImage`) + SoM placement.**
Extend the game-thread pass; dedup/placement algorithm. *Verify:* visual check marks land on button/slot centers at scaleFactor 2/3/4; downscale keeps marks aligned; end-to-end LLM loop opens inventory, reads a slot, clicks it, verifies.

**Phase 6 — Append-only trajectory (screen-before → action → screen-after).**
Reuse the packet-log ring pattern for GUI. *Verify:* replayable action history for debugging misclicks.

### Honest risks

1. **Mapping drift (already bit the briefs).** `client/` is vanilla-mapped; any refactor or a swapped GUI lib silently breaks reflection. Mitigation: Phase-0 accessor tests fail loudly; `gui_snapshot` reports which fields it couldn't read rather than throwing.
2. **Text-field enumeration is heuristic.** Fields are private on concrete screens with no global list; reflecting declared fields up the hierarchy misses fields held in collections or created lazily. Accept partial coverage; document it.
3. **Modded/OptiFine/custom screens** (`net.optifine.CustomGuis` present) may draw non-`GuiButton` widgets we can't see → they're invisible to the tree. Fall back to `capture_screen` and say so; do not pretend coverage (CLI-Anything "rendering gap" anti-pattern).
4. **Game-thread stalls.** Snapshot + FBO read + PNG encode on the render thread costs frames; enforce the `GameBridge` timeout and keep `onlyInteractable` default true.
5. **`windowClick` is server-visible and irreversible** (drops items, executes trades). This is precisely why actions sit at R1 with a dedicated privilege and warrant human-in-the-loop for destructive slots.
6. **Epoch races** within a single tick (transition mid-action). The structural fingerprint backstops identity comparison; on any doubt, reject and force re-snapshot.
7. **Dense-inventory ID confusion** in the overlay (SoM's known worst case). The parallel JSON list is the authority; the image is advisory — a mislabeled mark degrades to a re-snapshot, never a wrong click.

---

Key files touched: `core/.../mcp/ToolRegistry.java` (new tools), `core/.../security/Ring.java` + `ToolPolicy.java` + `Privilege` (rings/policy/`SE_GUI_INTERACT`), new `core/.../gui/GuiSnapshotService.java` + `GuiReflect.java`, extend `core/.../vision/ScreenCapture.java` (annotation overlay). Reflection targets live in `client/src/main/java/net/minecraft/client/gui/{GuiScreen,GuiButton,GuiTextField,ScaledResolution}.java`, `.../gui/inventory/GuiContainer.java`, `.../inventory/{Container,Slot}.java` — all vanilla-mapped.