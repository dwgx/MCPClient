package net.marcloud.mcp.core.gui;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.GameBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;

/**
 * Builds a {@link GuiSnapshot} of the live GUI on the GAME THREAD and owns the
 * monotonic {@link #currentEpoch() epoch} that the lead's action layer uses to
 * reject actions issued against a stale screen.
 *
 * <p>The epoch is bumped whenever the open {@code GuiScreen}'s IDENTITY changes
 * (a new screen object, not merely a mutation of the same one). Combined with
 * the cheap {@link #fingerprint(GuiScreen) structural fingerprint} (screen class
 * + button count + slot count) this lets the lead call
 * {@link #validate(GameAccess, int, String)} right before driving a handler and
 * bail if the screen changed under it.
 *
 * <p>All live reads are marshalled onto the game thread via
 * {@link GameBridge#onGameThread(java.util.concurrent.Callable, long)}.
 */
public final class GuiSnapshotService {

    private static final long DEFAULT_TIMEOUT_MS = 3000L;

    private final AtomicInteger epoch = new AtomicInteger(0);
    /** Identity of the screen the current epoch was minted for. */
    private volatile GuiScreen lastScreen;

    /** The epoch as of the last screen-identity check. */
    public int currentEpoch() {
        return epoch.get();
    }

    /**
     * Bump the epoch iff {@code screen} is a DIFFERENT object than the one we
     * last saw. Returns the (possibly bumped) epoch. Cheap; safe to call each
     * snapshot. {@code null} screen (no GUI open) is itself a distinct identity.
     */
    private int syncEpoch(GuiScreen screen) {
        if (screen != lastScreen) {
            lastScreen = screen;
            return epoch.incrementAndGet();
        }
        return epoch.get();
    }

    /**
     * Capture a snapshot of the currently open screen, marshalled onto the game
     * thread. Returns a "no screen" snapshot (null screen, empty elements) when
     * no GUI is open.
     *
     * @param game             the live game façade
     * @param onlyInteractable when true, skip invisible/disabled elements and labels
     */
    public GuiSnapshot snapshot(GameAccess game, boolean onlyInteractable) throws Exception {
        return GameBridge.onGameThread(
                () -> captureOnThread(game, onlyInteractable), DEFAULT_TIMEOUT_MS);
    }

    /**
     * The body that runs ON the game thread. Package-visible so it is exercised
     * directly by tests through {@link #buildSnapshot} with a synthetic screen.
     */
    private GuiSnapshot captureOnThread(GameAccess game, boolean onlyInteractable) {
        Minecraft mc = game.mc();
        GuiScreen screen = mc == null ? null : mc.currentScreen;
        boolean inWorld = game.isInWorld();
        Viewport viewport = viewportFor(mc, screen);
        return buildSnapshot(screen, inWorld, viewport, onlyInteractable);
    }

    /**
     * Pure snapshot builder: given an (already-resolved) screen, world flag and
     * viewport, produce the immutable snapshot. Handles the epoch bump. This is
     * the seam tests drive headless with a synthetic {@link GuiScreen}.
     *
     * @param screen           the open screen, or null when no GUI is open
     * @param inWorld          whether the player is in a world
     * @param viewport         resolved viewport geometry (never null)
     * @param onlyInteractable interactable-only filter
     */
    public GuiSnapshot buildSnapshot(GuiScreen screen, boolean inWorld,
                                     Viewport viewport, boolean onlyInteractable) {
        int ep = syncEpoch(screen);
        if (screen == null) {
            return new GuiSnapshot(ep, null, inWorld, false, null, viewport,
                    List.of(), fingerprintString(null, 0, 0), List.of());
        }
        GuiReflect.Extraction ex = GuiReflect.extract(screen, onlyInteractable);
        boolean isContainer = screen instanceof GuiContainer;
        String name = screen.getClass().getSimpleName();
        String fp = fingerprint(screen);
        return new GuiSnapshot(ep, name, inWorld, isContainer, name, viewport,
                ex.elements(), fp, ex.unreadable());
    }

    // ===== FINGERPRINT / STALE-EPOCH GUARD =====

    /**
     * Cheap structural signature of a screen: {@code simpleName#buttonCount#slotCount}.
     * Does NOT read element positions, so it's safe to compute frequently. A null
     * screen fingerprints as {@code "none#0#0"}.
     */
    public String fingerprint(GuiScreen screen) {
        if (screen == null) {
            return fingerprintString(null, 0, 0);
        }
        int buttons = countButtons(screen);
        int slots = countSlots(screen);
        return fingerprintString(screen.getClass().getSimpleName(), buttons, slots);
    }

    private static String fingerprintString(String name, int buttons, int slots) {
        return (name == null ? "none" : name) + "#" + buttons + "#" + slots;
    }

    @SuppressWarnings("unchecked")
    private static int countButtons(GuiScreen screen) {
        try {
            java.lang.reflect.Field f = GuiScreen.class.getDeclaredField("buttonList");
            f.setAccessible(true);
            Object v = f.get(screen);
            return v instanceof List<?> l ? l.size() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int countSlots(GuiScreen screen) {
        if (!(screen instanceof GuiContainer gc)) {
            return 0;
        }
        Container c = gc.inventorySlots;
        if (c == null) {
            return 0;
        }
        List<Slot> slots = c.inventorySlots;
        return slots == null ? 0 : slots.size();
    }

    /**
     * Validate an (epoch, fingerprint) pair the lead captured from an earlier
     * snapshot against the CURRENT live screen, on the game thread. Returns true
     * only if both the epoch and the structural fingerprint still match — i.e.
     * it is safe to drive a handler against that snapshot's element ids.
     */
    public boolean validate(GameAccess game, int expectedEpoch, String expectedFingerprint)
            throws Exception {
        return GameBridge.onGameThread(() -> {
            Minecraft mc = game.mc();
            GuiScreen screen = mc == null ? null : mc.currentScreen;
            // Re-sync so a changed identity is reflected before we compare.
            int ep = syncEpoch(screen);
            String fp = fingerprint(screen);
            return ep == expectedEpoch && fp.equals(expectedFingerprint);
        }, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Pure variant of {@link #validate} for headless tests: compare against a
     * supplied screen rather than the live game.
     */
    public boolean validateAgainst(GuiScreen screen, int expectedEpoch, String expectedFingerprint) {
        int ep = syncEpoch(screen);
        String fp = fingerprint(screen);
        return ep == expectedEpoch && fp.equals(expectedFingerprint);
    }

    // ===== VIEWPORT =====

    /**
     * Resolve viewport geometry. The screen's {@code width/height} are already in
     * scaled-GUI space. Framebuffer dims + scaleFactor come from a live
     * {@link ScaledResolution} when a Minecraft instance is available; otherwise
     * they degrade to {@code -1} / scaleFactor 1.
     */
    private static Viewport viewportFor(Minecraft mc, GuiScreen screen) {
        int w = screen == null ? 0 : screen.width;
        int h = screen == null ? 0 : screen.height;
        if (mc == null) {
            return new Viewport(w, h, 1, -1, -1);
        }
        try {
            ScaledResolution sr = new ScaledResolution(mc);
            int sf = sr.getScaleFactor();
            // If the screen didn't report a size, fall back to the scaled resolution.
            if (w == 0) {
                w = sr.getScaledWidth();
            }
            if (h == 0) {
                h = sr.getScaledHeight();
            }
            return new Viewport(w, h, sf, mc.displayWidth, mc.displayHeight);
        } catch (Throwable t) {
            return new Viewport(w, h, 1, -1, -1);
        }
    }

    /**
     * Build a {@link Viewport} directly (test seam / callers that already know
     * the geometry, e.g. the lead's overlay code).
     */
    public static Viewport viewport(int scaledWidth, int scaledHeight, int scaleFactor,
                                    int framebufferWidth, int framebufferHeight) {
        return new Viewport(scaledWidth, scaledHeight, scaleFactor,
                framebufferWidth, framebufferHeight);
    }
}
