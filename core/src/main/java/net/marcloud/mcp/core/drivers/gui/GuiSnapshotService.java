package net.marcloud.mcp.core.drivers.gui;

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
                    List.of(), null, fingerprintString(null, 0, 0, 0), List.of());
        }
        // Both passes share one unreadable sink, so a mapping drift in either shows up
        // once in the snapshot's own `unreadable` list rather than in two places.
        List<String> unreadable = new java.util.ArrayList<>();
        GuiReflect.Extraction ex = GuiReflect.extract(screen, onlyInteractable, unreadable);
        Container container = (screen instanceof GuiContainer gc) ? gc.inventorySlots : null;
        GuiPanelState panel = GuiPanelReflect.read(screen, container, unreadable);
        boolean isContainer = screen instanceof GuiContainer;
        String name = screen.getClass().getSimpleName();
        String fp = fingerprint(screen);
        return new GuiSnapshot(ep, name, inWorld, isContainer, name, viewport,
                ex.elements(), panel.hasContent() ? panel : null, fp, unreadable);
    }

    // ===== FINGERPRINT / STALE-EPOCH GUARD =====

    /**
     * Cheap structural signature of a screen: {@code simpleName#buttonCount#slotCount#token}.
     * Does NOT read element positions, so it's safe to compute frequently. A null
     * screen fingerprints as {@code "none#0#0#0"}.
     *
     * <p><b>Why the token exists.</b> The epoch alone cannot catch a screen that swaps its
     * content while keeping the same object — which is exactly what a QML {@code Loader}
     * does, and it is also what a screen that rebuilds its buttons in place does. Counting
     * elements alone is not enough either: two pages with four controls each produce the same
     * counts, the epoch does not move, and a click aimed at page A silently lands on page B.
     * The token is an order-sensitive hash of the element LABELS, so it changes when the
     * content changes and is stable when it does not.
     *
     * <p>Labels are the right input rather than ids or coordinates: a QML proxy's label is its
     * index path in the scene tree, which changes when the page changes, while two proxies on
     * the same page keep it; a vanilla button's label is its caption, which moves with the
     * button set. Coordinates were rejected because the fingerprint is recomputed often and
     * reading them is both the expensive part and the part most likely to shift under a resize
     * that did not change the content.
     */
    public String fingerprint(GuiScreen screen) {
        if (screen == null) {
            return fingerprintString(null, 0, 0, 0);
        }
        int buttons = 0;
        int token = 1;
        try {
            java.lang.reflect.Field f = GuiScreen.class.getDeclaredField("buttonList");
            f.setAccessible(true);
            if (f.get(screen) instanceof List<?> l) {
                buttons = l.size();
                // Hash in list order and fold the length in first, so a reorder is a change
                // too -- an order-sensitive hash is the difference between "same controls" and
                // "the same controls in the same places".
                token = buttons * 31 + 1;
                for (Object o : l) {
                    String label = "";
                    if (o instanceof net.minecraft.client.gui.GuiButton b
                            && b.displayString != null) {
                        label = b.displayString;
                    }
                    for (int i = 0; i < label.length(); i++) {
                        token = token * 31 + label.charAt(i);
                    }
                    token = token * 31 + 0x5F;
                }
            }
        } catch (Throwable t) {
            // A screen we cannot introspect still fingerprints; it just cannot be distinguished
            // from another such screen, which is the pre-existing behaviour.
            buttons = 0;
            token = 0;
        }
        int slots = countSlots(screen);
        return fingerprintString(screen.getClass().getSimpleName(), buttons, slots, token)
                + listFingerprintTerm(screen);
    }

    /**
     * The list term of a screen's fingerprint, or {@code ""} for a screen with no
     * lists.
     *
     * <p><b>Why scroll belongs in the fingerprint.</b> A snapshot is a claim about
     * what is addressable right now, and on a list screen the answer depends on the
     * scroll: at offset 0 rows 0-7 of the Controls list are addressable, at offset
     * 40 rows 2-9 are. An (epoch, fingerprint) minted at one scroll therefore
     * describes a different set of controls at another, exactly as a QML page swap
     * describes a different set — and the epoch cannot see it, because the screen
     * object never changed. Folding each list's class, row count and
     * {@code amountScrolled} in makes a scroll move the structural token, so a
     * reference planned before it is refused rather than acted on.
     *
     * <p>It reads offsets, not positions: the scroll is state the player changed on
     * purpose, while coordinates shift under a resize that changed nothing.
     *
     * <p>The suffix is omitted entirely when a screen has no lists, so every
     * list-free screen — and the null screen — keeps exactly the fingerprint it had.
     */
    private static String listFingerprintTerm(GuiScreen screen) {
        List<GuiListReflect.ListRef> lists =
                GuiListReflect.lists(screen, new java.util.ArrayList<>());
        if (lists.isEmpty()) {
            return "";
        }
        int token = lists.size() * 131 + 7;
        StringBuilder sb = new StringBuilder("@L");
        for (GuiListReflect.ListRef ref : lists) {
            int scroll = ref.list().getAmountScrolled();
            int rows = rowsOf(ref.list());
            sb.append(ref.list().getClass().getSimpleName())
                    .append(':').append(rows).append('@').append(scroll).append(';');
            token = token * 31 + scroll;
            token = token * 31 + rows;
        }
        sb.append(Integer.toHexString(token));
        return sb.toString();
    }

    private static int rowsOf(net.minecraft.client.gui.GuiSlot list) {
        for (Class<?> c = list.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod("getSize");
                m.setAccessible(true);
                Object v = m.invoke(list);
                return v instanceof Number n ? n.intValue() : 0;
            } catch (NoSuchMethodException e) {
                // walk up
            } catch (Throwable t) {
                return 0;
            }
        }
        return 0;
    }

    private static String fingerprintString(String name, int buttons, int slots, int token) {
        return (name == null ? "none" : name) + "#" + buttons + "#" + slots + "#"
                + Integer.toHexString(token);
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
