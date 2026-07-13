package net.marcloud.mcp.dwm.backend;

import java.util.List;

/**
 * Per-frame input snapshot marshalled from the shim's Mouse/Keyboard. Immediate
 * mode sees input as a per-frame snapshot; discrete char/key events are queued in
 * {@link #charEvents} / {@link #keyEvents} so a frame that spans several OS events
 * (routine when the game drops to low FPS) does not silently coalesce them.
 *
 * <p>Skeleton note: event record shapes are intentionally minimal here; the imgui
 * backend adapter fills them from the real input source.
 */
public record FrameInput(
        float pointerX,
        float pointerY,
        int buttonMask,
        float scrollX,
        float scrollY,
        List<Integer> charEvents,
        List<Integer> keyEvents) {

    public FrameInput {
        charEvents = charEvents == null ? List.of() : List.copyOf(charEvents);
        keyEvents = keyEvents == null ? List.of() : List.copyOf(keyEvents);
    }

    /** An empty input (no pointer movement, no events) — for headless/tests. */
    public static FrameInput none() {
        return new FrameInput(0f, 0f, 0, 0f, 0f, List.of(), List.of());
    }
}
