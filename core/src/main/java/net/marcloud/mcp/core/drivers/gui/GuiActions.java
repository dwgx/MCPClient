package net.marcloud.mcp.core.drivers.gui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.GameBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;

/**
 * Drives the REAL vanilla GUI handlers for the {@code gui_*} action tools. The
 * LLM never sends pixels — it sends an element id from a {@link GuiSnapshot}, and
 * this class re-resolves that id against the LIVE screen on the game thread,
 * recomputes the authoritative click-point from current geometry, and invokes the
 * genuine {@link GuiScreen#mouseClicked}/{@code keyTyped} (both protected in
 * vanilla 1.8.9, reached via reflection). All handler-driving runs on the game
 * thread.
 *
 * <p><b>Stale-action guard.</b> Every action carries the {@code epoch} +
 * {@linkplain GuiSnapshotService#fingerprint fingerprint} the snapshot was taken
 * at. The epoch is the screen's IDENTITY (bumped when a different {@code GuiScreen}
 * object is open) and the fingerprint is its ADDRESS SPACE (screen class, button
 * count, an order-sensitive hash of the button captions, slot count). Together they
 * answer one question: "are these element ids still pointing at what the agent read
 * them off?" They deliberately do NOT cover slot CONTENTS, because moving an item
 * is a normal consequence of clicking and treating it as staleness would make the
 * agent re-snapshot after every single inventory operation.
 *
 * <p><b>Confirmation.</b> A click that was driven is not a click that happened.
 * {@link ClickVerdict} has no {@code SENT} value; a slot click is CONFIRMED only
 * once the server's resync has landed and the slot reads differently, and anything
 * else is reported as {@code [UNVERIFIED]}.
 *
 * <p><b>The seven interaction primitives.</b> Vanilla's click modes are chosen by
 * inputs a plain {@code mouseClicked(x,y,0|1)} never supplies, so this class
 * drives the whole pointer lifecycle rather than one call:
 * <ul>
 *   <li><b>shift-click</b> (mode 1) — a modifier made genuinely present (below).</li>
 *   <li><b>click-outside-to-drop</b> (mode 4) — throws the carried stack.</li>
 *   <li><b>drag-split</b> (mode 5) — {@code mouseClicked}, then {@code
 *       mouseClickMove} per target, then {@code mouseReleased}.</li>
 *   <li><b>double-click-collect</b> (mode 6) — two presses inside vanilla's own
 *       250ms window, then a release.</li>
 *   <li><b>pick-block</b> (mode 3) — the button vanilla synthesises as
 *       {@code keyBindPickBlock.getKeyCode() + 100}, read from the live settings
 *       rather than assumed to be 2.</li>
 *   <li><b>scrolling</b> — the wheel, injected as the very mouse event
 *       {@code handleMouseInput} reads.</li>
 *   <li><b>creative tab switch</b> — a tab is not a button, so it is driven as a
 *       press+release on its computed point.</li>
 * </ul>
 *
 * <p><b>Modifiers are made genuinely present, not faked.</b> {@code GuiContainer}
 * reads them through {@code Keyboard.isKeyDown(42|54)}, and a private flag here
 * that vanilla never consults would make mode 1 <em>look</em> like mode 0 while
 * the server applied the plain-click result — a reported shift-click that was not
 * one. So a held modifier is written into the shim's own polled key-state buffer,
 * the buffer {@code isKeyDown} reads, for exactly the duration of the handler
 * call, and restored immediately afterwards.
 *
 * <p>Coordinates are scaled-GUI space (what {@code mouseClicked} consumes), so no
 * framebuffer/DPI conversion is ever traversed for an action — the class of bug
 * that plagues pixel-grounded computer-use agents cannot occur here.
 */
public final class GuiActions {

    private static final long TIMEOUT_MS = 3000L;

    /**
     * How long a slot click waits for the server's resync before giving up on
     * confirming it. A round trip plus a tick of granularity at each end is 200ms;
     * 600ms is the same budget {@code transfer_item} uses, and it is a floor on
     * honesty rather than a latency knob: reading sooner calls a rejection that has
     * merely not arrived yet a failure.
     */
    static final long CONFIRM_BUDGET_MS = 600L;

    /** Gap between re-reads while waiting for the resync. */
    private static final long CONFIRM_POLL_MS = 50L;

    private final GameAccess game;
    private final GuiSnapshotService svc;
    private final GuiTrajectory trajectory;

    public GuiActions(GameAccess game, GuiSnapshotService svc, GuiTrajectory trajectory) {
        this.game = game;
        this.svc = svc;
        this.trajectory = trajectory;
    }

    /** Outcome of an action: ok=false carries a human message explaining why. */
    public record Result(boolean ok, String message) {
        static Result ok(String m) {
            return new Result(true, m);
        }

        static Result fail(String m) {
            return new Result(false, m);
        }
    }

    /**
     * A click, and what could be established about it.
     *
     * @param verdict what the re-read proved; never "sent"
     * @param ok      whether the click was actually DRIVEN. A refusal is not ok. A
     *                driven click whose outcome is {@link ClickVerdict#NOT_CONFIRMED}
     *                is ok — the tool did what it was asked and the message says
     *                plainly that nothing about the outcome is claimed.
     */
    public record ClickResult(ClickVerdict verdict, boolean ok, String message) {
    }

    /**
     * A modifier key the agent holds while a handler runs.
     *
     * <p>The codes are vanilla's own literals from {@code GuiContainer:416/634}
     * ({@code Keyboard.isKeyDown(42) || Keyboard.isKeyDown(54)}) rather than the
     * shim's {@code KEY_LSHIFT}/{@code KEY_RSHIFT} constants, even though those
     * currently agree. Vanilla consults the raw numbers, so the numbers are what
     * must be written: if the shim ever renumbered a key, using its constants
     * would silently produce a plain click while the caller was told it shifted.
     */
    public enum Modifier {
        /** {@code Keyboard.isKeyDown(42) || isKeyDown(54)} — vanilla's shift test. */
        SHIFT(new int[] {42, 54}),
        /** {@code GuiScreen.isCtrlKeyDown()} — vanilla's ctrl test (29/157). */
        CTRL(new int[] {29, 157});

        private final int[] codes;

        Modifier(int[] codes) {
            this.codes = codes;
        }

        /** Every scancode vanilla's own predicate for this modifier consults. */
        public int[] codes() {
            return codes.clone();
        }
    }

    /**
     * A modifier held genuinely down for the duration of one handler call.
     *
     * <p>Vanilla has no "pretend shift is down" input, so the only honest way to
     * make its read return true is to make the read true: the shim keeps a
     * polled key-state buffer that {@code Keyboard.isKeyDown} reads directly, and
     * this writes the modifier's bytes into it, then puts back exactly what was
     * there. Restoring matters because the buffer is shared process-wide state —
     * leaving a phantom shift down would make every later click in the session a
     * shift-click.
     */
    static final class HeldModifiers implements AutoCloseable {

        private final ByteBuffer keys;
        private final int[] codes;
        private final byte[] prior;

        private HeldModifiers(ByteBuffer keys, int[] codes, byte[] prior) {
            this.keys = keys;
            this.codes = codes;
            this.prior = prior;
        }

        /**
         * Press {@code mods} in the shim's real key-state buffer.
         *
         * @throws IllegalStateException if the shim's buffer cannot be reached —
         *         never silently degrades to "no modifier", which is the failure
         *         that reports a shift-click that was a plain click
         */
        static HeldModifiers press(Set<Modifier> mods) {
            if (mods == null || mods.isEmpty()) {
                return null;
            }
            ByteBuffer buf = shimKeyDownBuffer();
            int[] all = new int[mods.size() * 2];
            int n = 0;
            for (Modifier m : mods) {
                for (int c : m.codes()) {
                    all[n++] = c;
                }
            }
            byte[] prior = new byte[all.length];
            for (int i = 0; i < all.length; i++) {
                int code = all[i];
                if (code < 0 || code >= buf.capacity()) {
                    throw new IllegalStateException("modifier scancode " + code
                            + " is outside the keyboard buffer (capacity " + buf.capacity() + ")");
                }
                prior[i] = buf.get(code);
                buf.put(code, (byte) 1);
            }
            return new HeldModifiers(buf, all, prior);
        }

        @Override
        public void close() {
            for (int i = 0; i < codes.length; i++) {
                keys.put(codes[i], prior[i]);
            }
        }
    }

    /**
     * The shim's polled key-state buffer, the exact storage
     * {@code Keyboard.isKeyDown} reads.
     *
     * <p>Resolved reflectively because it is private to the shim, and named once
     * so a rename fails loudly at the first modifier press rather than silently
     * degrading every shift-click into a plain one.
     */
    private static ByteBuffer shimKeyDownBuffer() {
        ByteBuffer cached = shimKeys;
        if (cached != null) {
            return cached;
        }
        try {
            Field f = org.lwjgl.input.Keyboard.class.getDeclaredField("keyDownBuffer");
            f.setAccessible(true);
            Object v = f.get(null);
            if (!(v instanceof ByteBuffer b)) {
                throw new IllegalStateException("Keyboard.keyDownBuffer is a "
                        + (v == null ? "null" : v.getClass().getName()) + ", not a ByteBuffer");
            }
            shimKeys = b;
            return b;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot reach the keyboard's key-state buffer, so a "
                    + "held modifier cannot be made genuinely present; refusing rather than "
                    + "reporting a shift-click that vanilla would have read as a plain click", e);
        }
    }

    /** Resolved once; the buffer is process-wide and never reallocated. */
    private static ByteBuffer shimKeys;

    /**
     * Re-reads one container slot. A seam so the confirmation wait can be driven
     * headlessly: a rule that can only be exercised against a live server is a rule
     * nobody checks, and that is exactly how "it was sent" crept into a green test.
     */
    interface SlotProbe {
        GuiStack read();
    }

    /** Injectable sleep, so a headless test of the wait costs no wall-clock time. */
    interface Sleeper {
        void sleep(long millis);
    }

    /** What the game-thread pass learned, so the caller can go and confirm it. */
    record Driven(ClickVerdict verdict, boolean ok, String message, String elementId,
                  int slotNumber, GuiStack before, String beforeFingerprint,
                  String afterFingerprint) {

        ClickResult toResult() {
            return new ClickResult(verdict, ok, message);
        }
    }

    // ===== CLICK =====

    /**
     * Click the element with {@code elementId} using {@code button} (0=left,
     * 1=right), after validating the snapshot is not stale. Re-resolves the
     * element's live click-point and invokes the real {@code mouseClicked}.
     *
     * <p>A slot click is then CONFIRMED or NOT_CONFIRMED by waiting for the
     * server's resync and re-reading the slot. A button click has no server round
     * trip of its own, so it is judged on whether the screen's structure changed —
     * which for most buttons it does not, and which is therefore reported as
     * {@code [UNVERIFIED]} rather than as success.
     */
    public ClickResult click(int epoch, String fingerprint, String elementId, int button)
            throws Exception {
        Driven d = GameBridge.onGameThread(
                () -> driveClick(liveScreen(), epoch, fingerprint, elementId, button),
                TIMEOUT_MS);
        ClickResult r = (d.slotNumber() >= 0 && wasDriven(d.verdict()))
                ? confirmSlot(d)
                : d.toResult();
        return record(d, r);
    }

    /**
     * Pure click body against an (already-resolved) screen — the seam tests drive
     * headless. There is no server to wait for here, so a slot click is judged on
     * the immediate client-side re-read only; {@link #click} is the path that adds
     * the resync wait.
     */
    ClickResult clickOnScreen(GuiScreen screen, int epoch, String fingerprint,
                              String elementId, int button) {
        Driven d = driveClick(screen, epoch, fingerprint, elementId, button);
        return record(d, d.toResult());
    }

    /**
     * Validate the reference, resolve the element, and drive the real handler.
     * Package-visible and game-thread-only: this is the one place a click happens.
     */
    Driven driveClick(GuiScreen screen, int epoch, String fingerprint, String elementId,
                      int button) {
        String before = svc.fingerprint(screen);
        String dead = guardMessage(screen, epoch, fingerprint);
        if (dead != null) {
            return driven(ClickVerdict.REFUSED_STALE, false, dead, elementId, -1, null, before, before);
        }
        GuiElement el = resolve(screen, elementId);
        if (el == null) {
            return driven(ClickVerdict.NO_ELEMENT, false, "no element '" + elementId
                    + "' on the current screen; call gui_snapshot again",
                    elementId, -1, null, before, before);
        }
        Point cp = el.clickPoint();
        if (cp == null) {
            return driven(ClickVerdict.NO_ELEMENT, false,
                    "element '" + elementId + "' has no click-point",
                    elementId, -1, null, before, before);
        }
        if (dropsCarriedStack(screen, cp)) {
            return driven(ClickVerdict.REFUSED_DESTRUCTIVE, false, "refused to click '"
                    + elementId + "' (" + el.name() + "): its click point (" + cp.x() + "," + cp.y()
                    + ") lies OUTSIDE the " + panelName(screen)
                    + ", and vanilla turns an outside click into a THROW of whatever the player "
                    + "is carrying into the world, with no undo. Click an element inside the "
                    + "panel, or move the item deliberately with the inventory tools",
                    elementId, -1, null, before, before);
        }

        int slotNumber = -1;
        GuiStack stackBefore = null;
        if (GuiElement.KIND_SLOT.equals(el.kind())) {
            slotNumber = slotNumberOf(el);
            stackBefore = readSlot(screen, slotNumber);
        }

        invokeMouseClicked(screen, cp.x(), cp.y(), button);
        String after = svc.fingerprint(screen);

        if (slotNumber >= 0) {
            GuiStack stackAfter = readSlot(screen, slotNumber);
            boolean moved = !Objects.equals(stackBefore, stackAfter);
            ClickVerdict v = moved ? ClickVerdict.CONFIRMED : ClickVerdict.NOT_CONFIRMED;
            String msg = moved
                    ? "clicked " + elementId + " ('" + el.name() + "') on " + panelName(screen)
                            + ": slot " + slotNumber + " held " + stackBefore + " and now reads "
                            + stackAfter + " -- confirmed by re-reading the slot"
                    : "clicked " + elementId + " ('" + el.name() + "') on " + panelName(screen)
                            + " with button " + button + " at (" + cp.x() + "," + cp.y() + "); slot "
                            + slotNumber + " still reads " + stackBefore + " [UNVERIFIED]";
            // A slot click is server-mediated, so "the handler ran" is not an outcome:
            // ok is CONFIRMED or nothing. confirmSlot() can still promote this to ok
            // once the server's resync lands, which is the only thing that may.
            return driven(v, v == ClickVerdict.CONFIRMED, msg, elementId, slotNumber,
                    stackBefore, before, after);
        }

        boolean changed = !before.equals(after);
        ClickVerdict v = changed ? ClickVerdict.CONFIRMED : ClickVerdict.NOT_CONFIRMED;
        String msg = changed
                ? "clicked " + elementId + " ('" + el.name() + "') on " + screen.getClass().getSimpleName()
                        + ": the screen structure changed " + before + " -> " + after
                : "clicked " + elementId + " ('" + el.name() + "') on " + screen.getClass().getSimpleName()
                        + " with button " + button + " at (" + cp.x() + "," + cp.y()
                        + "); the screen still reads " + after
                        + ". A button has no server round trip of its own, so an unchanged screen "
                        + "is NOT evidence of failure and NOT evidence of success [UNVERIFIED] — "
                        + "gui_trajectory shows what ran, gui_snapshot shows the result";
        return driven(v, true, msg, elementId, -1, null, before, after);
    }

    // ===== GESTURES =====

    /**
     * A full pointer interaction, as a human's hand produces one.
     *
     * <p>Vanilla does not deliver a click; it delivers a <em>sequence</em>. A
     * press is {@code mouseClicked}, a drag adds {@code mouseClickMove} calls,
     * and the effect of all but the simplest modes is only produced by the
     * trailing {@code mouseReleased} (modes 5 and 6 live there and nowhere
     * else). Driving one call and calling it a click is what left five of the
     * seven primitives unreachable.
     */
    public record Gesture(int button, Set<Modifier> modifiers, boolean release,
                          int repeatPresses, List<Point> dragPath, int wheelNotches) {

        public Gesture {
            modifiers = modifiers == null ? Set.of() : Set.copyOf(modifiers);
            dragPath = dragPath == null ? List.of() : List.copyOf(dragPath);
        }

        /** A plain left click with no modifier and no release: the historic shape. */
        public static Gesture plain(int button) {
            return new Gesture(button, Set.of(), false, 1, List.of(), 0);
        }

        /** A shift-click: press with shift genuinely held, then released. */
        public static Gesture shiftClick() {
            return new Gesture(0, Set.of(Modifier.SHIFT), true, 1, List.of(), 0);
        }

        /** Double-click: two presses inside vanilla's window, then a release (mode 6). */
        public static Gesture doubleClick() {
            return new Gesture(0, Set.of(), true, 2, List.of(), 0);
        }

        /**
         * Drag-split: press, sweep over each target, release (mode 5).
         *
         * <p>Vanilla's own rule, not an invention: a drag is only meaningful while
         * the player is CARRYING a stack. Pressing an empty slot with an empty
         * cursor is an ordinary pickup and starts no drag at all, so this refuses
         * rather than performing a pickup and calling it a split.
         */
        public static Gesture dragSplit(List<Point> path) {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("a drag-split needs at least one target slot");
            }
            return new Gesture(0, Set.of(), true, 1, path, 0);
        }

        /** The wheel: {@code notches} < 0 scrolls up, > 0 scrolls down. */
        public static Gesture scroll(int notches) {
            return new Gesture(0, Set.of(), false, 0, List.of(), notches);
        }
    }

    /**
     * Drive a full gesture on the live screen, with the confirmation wait.
     *
     * <p>Each interaction is confirmed by the read-back that can actually
     * establish it, and by nothing weaker:
     * <ul>
     *   <li><b>shift-click</b> — the SOURCE slot re-reads differently. A
     *       shift-click's whole effect is that the source empties.</li>
     *   <li><b>click-outside</b> — the CARRIED stack re-reads empty. That is the
     *       drop; the client applies it locally, so no resync is needed.</li>
     *   <li><b>drag-split</b> — see {@link #driveDrag}; the two halves differ.</li>
     *   <li><b>double-click</b> — the clicked slot re-reads different.</li>
     *   <li><b>pick-block</b> — the HELD ITEM re-reads different. The task text
     *       for the change is the item in the player's hand.</li>
     *   <li><b>scroll</b> — the screen's scroll offset re-reads different.</li>
     *   <li><b>creative tab</b> — the selected tab index re-reads different.</li>
     * </ul>
     */
    public ClickResult gesture(int epoch, String fingerprint, String elementId, Gesture g)
            throws Exception {
        Driven d = GameBridge.onGameThread(
                () -> driveGesture(liveScreen(), epoch, fingerprint, elementId, g), TIMEOUT_MS);
        return record(d, d.toResult());
    }

    /** Pure gesture body against a supplied screen — the seam the tests drive. */
    ClickResult gestureOnScreen(GuiScreen screen, int epoch, String fingerprint,
                                String elementId, Gesture g) {
        Driven d = driveGesture(screen, epoch, fingerprint, elementId, g);
        return record(d, d.toResult());
    }

    /**
     * The one place a gesture happens. Game-thread only.
     *
     * <p>The stale guard runs first and identically to {@link #driveClick}: a
     * gesture is an action on a reference just as a click is, and a
     * shift-click aimed at a screen that has since been rebuilt would move the
     * wrong item.
     */
    Driven driveGesture(GuiScreen screen, int epoch, String fingerprint, String elementId,
                        Gesture g) {
        String before = svc.fingerprint(screen);
        String dead = guardMessage(screen, epoch, fingerprint);
        if (dead != null) {
            return driven(ClickVerdict.REFUSED_STALE, false, dead, elementId, -1, null, before, before);
        }
        if (g == null) {
            return driven(ClickVerdict.NO_ELEMENT, false, "no gesture given", elementId, -1, null,
                    before, before);
        }

        if (g.wheelNotches() != 0) {
            // A named list scrolls itself; an unnamed wheel (the creative list) keeps
            // the screen-level path, whose consumer vanilla also wrote.
            return GuiListReflect.listIndexOf(elementId) >= 0
                    ? driveListScroll(screen, elementId, g, before)
                    : driveScroll(screen, g, before);
        }
        if (isCreativeTabClick(screen, elementId)) {
            return driveCreativeTab(screen, elementId, g, before);
        }
        // A row id names a row of a list's DATA, not a pixel, so it gets its own path:
        // the same press and release, but judged by re-reading the row.
        if (GuiListReflect.rowRefOf(elementId) != null) {
            return driveRow(screen, elementId, g, before);
        }
        if (GuiListReflect.headerRefOf(elementId) != null) {
            return driveHeader(screen, elementId, g, before);
        }
        return drivePointer(screen, epoch, fingerprint, elementId, g, before);
    }

    /**
     * Press / move / release, with the modifier held across every call.
     *
     * <p>The modifier is held for the WHOLE sequence, not just the press:
     * {@code mouseReleased:634} re-reads the same shift test to decide mode 1,
     * so releasing outside the hold would turn a shift-click's release half into
     * a plain click.
     */
    private Driven drivePointer(GuiScreen screen, int epoch, String fingerprint,
                                String elementId, Gesture g, String before) {
        GuiElement el = resolve(screen, elementId);
        if (el == null) {
            return driven(ClickVerdict.NO_ELEMENT, false, "no element '" + elementId
                    + "' on the current screen; call gui_snapshot again",
                    elementId, -1, null, before, before);
        }
        Point cp = el.clickPoint();
        if (cp == null) {
            return driven(ClickVerdict.NO_ELEMENT, false,
                    "element '" + elementId + "' has no click-point", elementId, -1, null,
                    before, before);
        }
        // The outside-the-panel throw is a mode-4 click, and it is the one gesture
        // that destroys something. It is the SAME refusal as a plain click, kept.
        if (dropsCarriedStack(screen, cp)) {
            return driven(ClickVerdict.REFUSED_DESTRUCTIVE, false, "refused to press '"
                    + elementId + "' (" + el.name() + "): its click point (" + cp.x() + "," + cp.y()
                    + ") lies OUTSIDE the " + panelName(screen) + ", and vanilla turns an outside "
                    + "click into a THROW of whatever the player is carrying into the world, with "
                    + "no undo. Click an element inside the panel",
                    elementId, -1, null, before, before);
        }

        boolean isSlot = GuiElement.KIND_SLOT.equals(el.kind());
        int slotNumber = isSlot ? slotNumberOf(el) : -1;
        GuiStack slotBefore = slotNumber >= 0 ? readSlot(screen, slotNumber) : null;
        GuiStack carriedBefore = carriedStack();
        GuiStack heldBefore = heldItem();

        HeldModifiers held;
        try {
            held = HeldModifiers.press(g.modifiers());
        } catch (RuntimeException e) {
            // Refuse rather than silently perform the un-modified version.
            return driven(ClickVerdict.NOT_CONFIRMED, false, "refused " + elementId
                    + ": " + e.getMessage(), elementId, -1, null, before, before);
        }
        try {
            for (int i = 0; i < Math.max(1, g.repeatPresses()); i++) {
                invokeMouseClicked(screen, cp.x(), cp.y(), g.button());
                if (g.repeatPresses() > 1 && i == 0) {
                    // Vanilla's own double-click window: lastClickTime is stamped
                    // inside mouseClicked, so the second press must see the first.
                    // No sleep is needed because both happen on this thread
                    // microseconds apart, which is inside the 250ms window.
                    continue;
                }
            }
            for (Point p : g.dragPath()) {
                invokeMouseClickMove(screen, p.x(), p.y(), g.button(), 0L);
            }
            if (g.release()) {
                Point end = g.dragPath().isEmpty() ? cp : g.dragPath().get(g.dragPath().size() - 1);
                invokeMouseReleased(screen, end.x(), end.y(), g.button());
            }
        } finally {
            if (held != null) {
                held.close();
            }
        }

        String after = svc.fingerprint(screen);
        return judge(screen, el, g, cp, slotNumber, slotBefore, carriedBefore, heldBefore,
                before, after);
    }

    /**
     * Drive a press on one row of a list, and report the verdict from the read-back
     * that can actually establish it.
     *
     * <p><b>The press goes through the screen's own {@code mouseClicked},</b> so
     * {@code GuiControls} really does hand it to {@code keyBindingList.mouseClicked}
     * and {@code GuiListExtended} really does call the row's
     * {@code IGuiListEntry.mousePressed}. Nothing about a row is simulated.
     *
     * <p><b>The release is not optional here, unlike for a button.</b> When
     * {@code GuiListExtended.mouseClicked} sees an entry accept the press it calls
     * {@code setEnabled(false)}, and the list is only re-enabled by
     * {@code mouseReleased}. A row press without its release therefore leaves the
     * list disabled — a real, observable, vanilla-caused half-click — so the release
     * half is always driven for a row and the verdict says so.
     *
     * <p><b>Three refusals, all before vanilla sees anything:</b> a row id whose
     * list is gone, a row that has scrolled out of view, and a row on a list vanilla
     * gives no click handler at all. The second is the one that matters most: a row
     * id names a row of the list's DATA, so a scroll cannot silently redirect it,
     * but only because this refuses instead of falling through to a bare
     * NO_ELEMENT and leaving the agent to conclude the control does not exist.
     */
    private Driven driveRow(GuiScreen screen, String elementId, Gesture g, String before) {
        java.util.List<String> sink = new java.util.ArrayList<>();
        GuiElement el = resolve(screen, elementId);
        if (el == null) {
            String why = GuiListReflect.explainMissing(screen, elementId, sink);
            return driven(ClickVerdict.NO_ELEMENT, false, why != null ? why
                    : "no element '" + elementId + "' on the current screen; call gui_snapshot again",
                    elementId, -1, null, before, before);
        }
        Point cp = el.clickPoint();
        if (cp == null) {
            return driven(ClickVerdict.NO_ELEMENT, false,
                    "row '" + elementId + "' has no click point", elementId, -1, null,
                    before, before);
        }
        if (dropsCarriedStack(screen, cp)) {
            return driven(ClickVerdict.REFUSED_DESTRUCTIVE, false, "refused to press row '"
                    + elementId + "': its click point (" + cp.x() + "," + cp.y()
                    + ") lies OUTSIDE the " + panelName(screen) + ", and vanilla turns an outside "
                    + "click into a THROW of whatever the player is carrying into the world, with "
                    + "no undo", elementId, -1, null, before, before);
        }
        if (!el.actions().contains("click")) {
            return driven(ClickVerdict.NOT_CONFIRMED, false, refusalReason(el),
                    elementId, -1, null, before, before);
        }

        String readBefore = rowRead(el);
        // GuiListExtended.mouseClicked opens with isMouseYWithinSlotBounds(this.mouseY),
        // and mouseX/mouseY on a list are set only by drawScreen. So the virtual
        // cursor is parked on this row for the press and the release, exactly as a
        // human's cursor is over the row they click, and handed back afterwards.
        // Without it a row click is refused for a reason that has nothing to do with
        // the row: "this list is not under the mouse".
        net.minecraft.client.gui.GuiSlot list = rowList(screen, elementId);
        int priorMX = list == null ? 0 : intField(list, "mouseX");
        int priorMY = list == null ? 0 : intField(list, "mouseY");
        HeldModifiers held = null;
        try {
            held = HeldModifiers.press(g.modifiers());
            if (list != null) {
                setIntFieldsQuietly(list, "mouseX", cp.x(), "mouseY", cp.y());
            }
            invokeMouseClicked(screen, cp.x(), cp.y(), g.button());
            // Always the release half: GuiListExtended disables the list on an accepted
            // press and only mouseReleased turns it back on.
            invokeMouseReleased(screen, cp.x(), cp.y(), g.button());
        } catch (RuntimeException e) {
            return driven(ClickVerdict.NOT_CONFIRMED, false, "row press on '" + elementId
                    + "' failed: " + rootMsg(e), elementId, -1, null, before, before);
        } finally {
            if (list != null) {
                setIntFieldsQuietly(list, "mouseX", priorMX, "mouseY", priorMY);
            }
            if (held != null) {
                held.close();
            }
        }
        String after = svc.fingerprint(screen);
        // An INCOMPATIBLE resource pack does not move on the press: vanilla opens a
        // GuiYesNo and moves the pack in the dialog's confirmClicked. The observable
        // for that press is therefore the SCREEN, not the row, and reading only the row
        // would report a dialog that opened as "nothing happened".
        GuiScreen opened = currentScreenOrNull();
        if (opened != null && opened != screen) {
            return driven(ClickVerdict.CONFIRMED, true, "pressed row " + describeRow(el)
                    + " at (" + cp.x() + "," + cp.y() + "): the open screen changed to "
                    + opened.getClass().getSimpleName()
                    + " -- confirmed by re-reading the open screen. The pack itself has NOT "
                    + "moved: vanilla defers an incompatible pack's move to the "
                    + "confirmation dialog's confirmClicked, so answer that dialog to finish",
                    elementId, -1, null, before, after);
        }
        GuiElement now = resolve(screen, elementId);
        String readAfter = now == null ? readBefore : rowRead(now);
        if (!readBefore.equals(readAfter)) {
            return driven(ClickVerdict.CONFIRMED, true, "pressed row " + describeRow(el)
                    + " at (" + cp.x() + "," + cp.y() + "): it now reads " + readAfter
                    + " (was " + readBefore + ") -- confirmed by re-reading the row itself",
                    elementId, -1, null, before, after);
        }
        return driven(ClickVerdict.NOT_CONFIRMED, false, "pressed row " + describeRow(el)
                + " at (" + cp.x() + "," + cp.y() + "); the row still reads " + readAfter
                + ". The press and its release reached vanilla, but this row's own entry "
                + "accepted nothing at that point [UNVERIFIED]",
                elementId, -1, null, before, after);
    }

    /** The live open screen, or null when no client is attached to read it from. */
    private static GuiScreen currentScreenOrNull() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc == null ? null : mc.currentScreen;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Press a control in a list's HEADER band, and report the verdict from the read-back
     * that can establish it.
     *
     * <p><b>Same mechanism as the wheel, not a third one.</b> Vanilla routes a click
     * above the first row to {@code func_148132_a} from inside
     * {@code GuiSlot.handleMouseInput} — the very method whose wheel branch the scroll
     * path already drives — and it reads {@code Mouse.getEventButton()} and
     * {@code Mouse.getEventButtonState()}, the same shim seam the wheel notch uses. So
     * the press is injected as one mouse event and {@code handleMouseInput} is invoked.
     *
     * <p>The list's own {@code handleMouseInput} is called rather than the screen's,
     * and that difference is deliberate: {@code GuiScreen.handleMouseInput} would, on
     * seeing a press event, dispatch a real {@code mouseClicked} at a scaled position
     * derived from {@code Mouse.getEventX/Y}. That is a separate input event with
     * effects of its own, and pressing a header target is not it. The call made here is
     * byte-for-byte the one {@code GuiStats.handleMouseInput} makes on its grid.
     *
     * <p><b>The observable is the ROW ORDER</b>, and nothing softer. The handler sets a
     * private column index and re-sorts; the sound it plays is not readable, and the
     * private field is not published. The order of the grid's rows IS a world fact and
     * IS observable, so that is what CONFIRMED is decided on. A press that re-sorts into
     * the order it was already in reports NOT_CONFIRMED rather than a click that did
     * nothing — which is the real case for the third press, since vanilla's column
     * cycles ascending to descending to unsorted.
     */
    private Driven driveHeader(GuiScreen screen, String elementId, Gesture g, String before) {
        java.util.List<String> sink = new java.util.ArrayList<>();
        GuiElement el = resolve(screen, elementId);
        if (el == null) {
            return driven(ClickVerdict.NO_ELEMENT, false,
                    "no header control '" + elementId + "' on the current screen; call "
                            + "gui_snapshot again", elementId, -1, null, before, before);
        }
        Point cp = el.clickPoint();
        net.minecraft.client.gui.GuiSlot list = headerList(screen, elementId);
        if (cp == null || list == null) {
            return driven(ClickVerdict.NO_ELEMENT, false,
                    "header control '" + elementId + "' is not reachable on this screen",
                    elementId, -1, null, before, before);
        }
        String orderBefore = rowOrder(screen, elementId);

        int priorWheel = intStaticField(org.lwjgl.input.Mouse.class, "eventDWheel");
        int priorButton = intStaticField(org.lwjgl.input.Mouse.class, "eventButton");
        boolean priorState = boolStatic(org.lwjgl.input.Mouse.class, "eventState");
        int priorMX = intField(list, "mouseX");
        int priorMY = intField(list, "mouseY");
        RuntimeException thrown = null;
        try {
            setIntFieldsQuietly(list, "mouseX", cp.x(), "mouseY", cp.y());
            setStaticField(org.lwjgl.input.Mouse.class, "eventButton", 0);
            setBoolStatic(org.lwjgl.input.Mouse.class, "eventState", true);
            invokeListMouseInput(list);
        } catch (RuntimeException e) {
            // Deliberately not returned here. This handler re-sorts and THEN plays a
            // sound, so an exception from anything after the sort is not a failure of
            // the sort. Bailing on the exception would report a re-order that really
            // happened as a failed press -- the exact inversion of this project's rule.
            thrown = e;
        } finally {
            setStaticFieldQuietly(org.lwjgl.input.Mouse.class, "eventDWheel", priorWheel);
            setStaticFieldQuietly(org.lwjgl.input.Mouse.class, "eventButton", priorButton);
            setBoolStaticQuietly(org.lwjgl.input.Mouse.class, "eventState", priorState);
            setIntFieldsQuietly(list, "mouseX", priorMX, "mouseY", priorMY);
        }
        String after = svc.fingerprint(screen);
        String orderAfter = rowOrder(screen, elementId);
        if (!orderBefore.equals(orderAfter)) {
            return driven(ClickVerdict.CONFIRMED, true, "pressed header control '" + elementId
                    + "' (" + el.name() + ") at (" + cp.x() + "," + cp.y() + "): the list's row "
                    + "order changed -- confirmed by re-reading the rows themselves"
                    + (thrown == null ? ""
                            : ". The handler then threw (" + rootMsg(thrown)
                            + ") AFTER the re-sort, most likely in the click sound it plays; "
                            + "the order is the fact here, so the throw does not undo it"),
                    elementId, -1, null, before, after);
        }
        if (thrown != null) {
            return driven(ClickVerdict.NOT_CONFIRMED, false, "header press on '" + elementId
                    + "' failed: " + rootMsg(thrown) + ", and the row order still reads "
                    + orderAfter, elementId, -1, null, before, after);
        }
        return driven(ClickVerdict.NOT_CONFIRMED, false, "pressed header control '" + elementId
                + "' (" + el.name() + ") at (" + cp.x() + "," + cp.y()
                + "); the row order still reads " + orderAfter
                + ". Either the press fell outside every range this handler answers to ("
                + GuiListReflect.SORT_RULE + "), or the column was already in the state this "
                + "press selects and vanilla cycles a column through ascending -> descending "
                + "-> unsorted, so a third press restores the original order [UNVERIFIED]",
                elementId, -1, null, before, after);
    }

    /**
     * The list's rows in order, as a comparable string: the honest read-back for a
     * header control, which re-sorts rather than changing any single row.
     */
    private static String rowOrder(GuiScreen screen, String elementId) {
        GuiListReflect.HeaderRef ref = GuiListReflect.headerRefOf(elementId);
        if (ref == null) {
            return "";
        }
        java.util.List<GuiListReflect.ListRef> all =
                GuiListReflect.lists(screen, new java.util.ArrayList<>());
        if (ref.listIndex() >= all.size()) {
            return "";
        }
        int listIndex = ref.listIndex();
        StringBuilder sb = new StringBuilder();
        for (GuiElement e : GuiReflect.extract(screen, false).elements()) {
            if (!GuiElement.KIND_ROW.equals(e.kind())
                    || !Integer.valueOf(listIndex).equals(e.attributes().get("listIndex"))) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.name());
        }
        return sb.toString();
    }

    private static net.minecraft.client.gui.GuiSlot headerList(GuiScreen screen, String elementId) {
        GuiListReflect.HeaderRef ref = GuiListReflect.headerRefOf(elementId);
        if (ref == null) {
            return null;
        }
        GuiListReflect.ListRef target = GuiListReflect.refFor(screen, ref.listIndex(),
                new java.util.ArrayList<>());
        return target == null ? null : target.list();
    }

    /** Invoke a list's own {@code handleMouseInput} — the call the screen makes on it. */
    private static void invokeListMouseInput(net.minecraft.client.gui.GuiSlot list) {
        Method m = methodInHierarchy(list.getClass(), "handleMouseInput");
        if (m == null) {
            throw new IllegalStateException("handleMouseInput() not found on "
                    + list.getClass().getName());
        }
        try {
            m.setAccessible(true);
            m.invoke(list);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("handleMouseInput failed: "
                    + (e.getCause() != null ? e.getCause() : e), e);
        }
    }

    private static void setBoolStatic(Class<?> owner, String name, boolean v) {
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(null, v);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot set " + name + ": " + rootMsg(t));
        }
    }

    private static boolean boolStatic(Class<?> owner, String name) {
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void setBoolStaticQuietly(Class<?> owner, String name, boolean v) {
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(null, v);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /** The list a row id names, or null when the id is not a row id. */
    private static net.minecraft.client.gui.GuiSlot rowList(GuiScreen screen, String elementId) {
        GuiListReflect.RowRef ref = GuiListReflect.rowRefOf(elementId);
        if (ref == null) {
            return null;
        }
        GuiListReflect.ListRef target = GuiListReflect.refFor(screen, ref.listIndex(),
                new java.util.ArrayList<>());
        return target == null ? null : target.list();
    }

    /**
     * Why a row cannot be pressed, distinguishing the two reasons that look alike from
     * the outside: the list has no press handler at all, versus the row's own entry
     * refuses every press. A resource pack is the second case — the built-in Default
     * pack returns false from vanilla's {@code func_148310_d}, so it is refused at any
     * position — and reporting that as "this screen has no click handler" would send an
     * agent looking for a wiring fault that does not exist.
     */
    private static String refusalReason(GuiElement el) {
        String who = describeRow(el);
        Object kind = el.attributes().get("rowKind");
        if ("category".equals(kind)) {
            return "row " + who + " is a section heading: vanilla's CategoryEntry returns "
                    + "false from mousePressed, so it is readable and not pressable";
        }
        if ("fixed".equals(kind)) {
            return "row " + who + " is a pack vanilla refuses at EVERY position: it returns "
                    + "false from func_148310_d, the first half of the guard in "
                    + "ResourcePackListEntry.mousePressed, and it also blocks reordering "
                    + "past it. The position is not the problem here";
        }
        if (Boolean.FALSE.equals(el.attributes().get("hasPressHandler"))) {
            return "row " + who + " belongs to a list vanilla gives no click handler at all: "
                    + "only GuiListExtended overrides GuiSlot.mouseClicked, so a row of a plain "
                    + "GuiSlot is drawn and read but never pressed. It is still addressable and "
                    + "its contents are still readable; it just cannot be activated by a click";
        }
        return "row " + who + " is published as read-only, and vanilla has no press handler "
                + "that would do anything with it";
    }

    /**
     * The observable a row click can change, as one comparable string: what the row
     * is called, what value it currently holds, and whether it is armed. For a key
     * binding that is the arming flag and the bound key; for an option row it is the
     * option's new value. This is the read-back, not the dispatch, that decides
     * CONFIRMED — a press that changed nothing observable is NOT_CONFIRMED even
     * though vanilla really did receive it.
     */
    private static String rowRead(GuiElement el) {
        return "name='" + el.name() + "' value='" + el.value() + "' armed=" + el.state().focused();
    }

    /** A human description of a row, for a verdict message. */
    private static String describeRow(GuiElement el) {
        Object listClass = el.attributes().get("listClass");
        Object rowIndex = el.attributes().get("rowIndex");
        String label = el.name().isEmpty() ? "(unlabelled)" : "'" + el.name() + "'";
        return "'" + el.id() + "' = row " + rowIndex + " of "
                + (listClass == null ? "its list" : String.valueOf(listClass)) + ", " + label;
    }

    /**
     * Turn a driven gesture into an honest verdict, using the read-back that can
     * actually establish THAT gesture.
     *
     * <p>Each branch re-reads the one thing whose change would prove the effect,
     * and every branch that cannot prove it says so in the message rather than
     * reporting success.
     */
    private Driven judge(GuiScreen screen, GuiElement el, Gesture g, Point cp,
                         int slotNumber, GuiStack slotBefore, GuiStack carriedBefore,
                         GuiStack heldBefore, String before, String after) {
        String who = elementId(el) + " ('" + el.name() + "')";

        // pick-block: the observable is the item in the player's HAND.
        if (g.button() == pickBlockButton()) {
            GuiStack now = heldItem();
            if (!Objects.equals(heldBefore, now)) {
                return driven(ClickVerdict.CONFIRMED, true, "pick-block on " + who
                        + ": the held item changed " + heldBefore + " -> " + now
                        + " -- confirmed by re-reading the item in the player's hand",
                        el.id(), -1, null, before, after);
            }
            return driven(ClickVerdict.NOT_CONFIRMED, false, "pick-block on " + who
                    + " at (" + cp.x() + "," + cp.y() + "); the held item still reads " + now
                    + ". The server answers a pick-block with the block's item, and if it has not "
                    + "arrived the hand is unchanged [UNVERIFIED]", el.id(), -1, null, before, after);
        }

        // A drag-split is confirmed by its DESTINATION slots, not the source.
        if (!g.dragPath().isEmpty()) {
            return judgeDrag(screen, g, cp, carriedBefore, before, after);
        }

        // A slot gesture (incl. shift-click) is confirmed by the SOURCE slot.
        if (slotNumber >= 0) {
            GuiStack now = readSlot(screen, slotNumber);
            if (!Objects.equals(slotBefore, now)) {
                return driven(ClickVerdict.CONFIRMED, true, describe(g) + " on " + who
                        + ": slot " + slotNumber + " reads " + slotBefore + " -> " + now
                        + " -- confirmed by re-reading the slot",
                        el.id(), slotNumber, slotBefore, before, after);
            }
            return driven(ClickVerdict.NOT_CONFIRMED, false, describe(g) + " on " + who
                    + " at (" + cp.x() + "," + cp.y() + "); slot " + slotNumber
                    + " still reads " + now + " [UNVERIFIED]", el.id(), slotNumber, slotBefore,
                    before, after);
        }

        boolean changed = !before.equals(after);
        return driven(changed ? ClickVerdict.CONFIRMED : ClickVerdict.NOT_CONFIRMED, changed,
                describe(g) + " on " + who + " at (" + cp.x() + "," + cp.y() + "); the screen "
                        + (changed ? "changed " + before + " -> " + after
                        : "still reads " + after)
                        + (changed ? " -- confirmed by re-reading the screen"
                        : ". A button has no server round trip of its own, so an unchanged screen "
                        + "is NOT evidence of failure and NOT evidence of success [UNVERIFIED]"),
                el.id(), -1, null, before, after);
    }

    /**
     * A drag-split's verdict, and the reason it cannot simply be "the slot changed".
     *
     * <p><b>What "confirmed" can mean here.</b> A drag is TWO events at different
     * times: the press arms the drag and collects no items at all (it only sets
     * {@code dragSplitting} and clears the slot set), and the RELEASE is what
     * sends the mode-5 packets that actually redistribute the stack. So the press
     * half is, on its own, worth nothing observable — reporting it as a
     * successful drag is precisely the "it did not throw" lie this class exists to
     * avoid. The honest reading is therefore: the press and sweep were driven,
     * and confirmation is the DESTINATION slots re-reading differently after the
     * server's resync. If they do not, that is NOT_CONFIRMED with the split
     * reported as driven-but-unproven, never as done.
     */
    private Driven judgeDrag(GuiScreen screen, Gesture g, Point cp, GuiStack carriedBefore,
                             String before, String after) {
        StringBuilder moved = new StringBuilder();
        int changed = 0;
        for (Point p : g.dragPath()) {
            Slot s = slotAt(screen, p);
            if (s == null) {
                continue;
            }
            String now = String.valueOf(readSlot(screen, s.slotNumber));
            if (moved.length() > 0) {
                moved.append(", ");
            }
            moved.append(s.slotNumber).append('=').append(now);
        }
        // A destination slot that differs from empty proves items arrived.
        for (Point p : g.dragPath()) {
            Slot s = slotAt(screen, p);
            if (s != null && readSlot(screen, s.slotNumber).count() > 0) {
                changed++;
            }
        }
        if (changed > 0) {
            return driven(ClickVerdict.CONFIRMED, true, "drag-split across " + g.dragPath().size()
                    + " slot(s) from (" + cp.x() + "," + cp.y() + "): " + changed
                    + " destination slot(s) now hold items [" + moved + "]"
                    + " -- confirmed by re-reading the DESTINATION slots, which is the only "
                    + "read-back a drag has: the press half of a vanilla drag moves nothing by "
                    + "itself and only the release sends the mode-5 packets",
                    null, -1, null, before, after);
        }
        return driven(ClickVerdict.NOT_CONFIRMED, false, "drag-split across " + g.dragPath().size()
                + " slot(s) from (" + cp.x() + "," + cp.y() + "): the press and sweep were driven "
                + "and the release was sent, but after the resync window no destination slot "
                + "holds items [" + moved + "]. Stated precisely: the PRESS half of a vanilla drag "
                + "collects nothing on its own, so only the release could have moved anything, and "
                + "it did not [UNVERIFIED]. The carried stack still reads " + carriedStack(),
                null, -1, null, before, after);
    }

    /**
     * Drive the scroll wheel as the mouse event {@code handleMouseInput} reads.
     *
     * <p>Not a reimplementation: the wheel is injected as the shim's own current
     * event slot — the same {@code eventDWheel} that {@code Mouse.next()} fills
     * from a real GLFW scroll — and then the screen's own
     * {@code handleMouseInput} is invoked. So the consumer that acts on a wheel
     * is whichever one vanilla wrote ({@code GuiContainerCreative} for the
     * creative list, {@code GuiSlot} for a scrolled list), not a guess here.
     *
     * <p>Worth stating plainly: a plain {@code GuiContainer} does NOT override
     * {@code handleMouseInput}, so for a chest or a crafting table the wheel
     * genuinely does nothing in vanilla. This reports that honestly instead of
     * claiming a scroll that no code consumed.
     */
    private Driven driveScroll(GuiScreen screen, Gesture g, String before) {
        int prior = intStaticField(org.lwjgl.input.Mouse.class, "eventDWheel");
        String offsetBefore = scrollOffset(screen);
        try {
            setStaticField(org.lwjgl.input.Mouse.class, "eventDWheel", g.wheelNotches());
            invokeHandleMouseInput(screen);
        } catch (RuntimeException e) {
            return driven(ClickVerdict.NOT_CONFIRMED, false, "scroll of " + g.wheelNotches()
                    + " notch(es) failed: " + rootMsg(e), null, -1, null, before, before);
        } finally {
            setStaticFieldQuietly(org.lwjgl.input.Mouse.class, "eventDWheel", prior);
        }
        String after = svc.fingerprint(screen);
        String offsetAfter = scrollOffset(screen);
        boolean moved = !Objects.equals(offsetBefore, offsetAfter);
        if (moved) {
            return driven(ClickVerdict.CONFIRMED, true, "scrolled " + g.wheelNotches()
                    + " notch(es) on " + screen.getClass().getSimpleName()
                    + ": the scroll offset moved " + offsetBefore + " -> " + offsetAfter
                    + " -- confirmed by re-reading the screen's own scroll offset",
                    null, -1, null, before, after);
        }
        return driven(ClickVerdict.NOT_CONFIRMED, false, "scrolled " + g.wheelNotches()
                + " notch(es) on " + screen.getClass().getSimpleName()
                + "; the scroll offset still reads " + offsetAfter
                + ". This screen consumes no wheel event -- vanilla's plain GuiContainer does not "
                + "override handleMouseInput, so there is no scrolling here to confirm [UNVERIFIED]"
                + (offsetAfter.isEmpty() ? " (this screen exposes no scroll offset at all)" : ""),
                null, -1, null, before, after);
    }

    /**
     * Scroll a NAMED list, through the same wheel path a human's wheel takes.
     *
     * <p>Nothing here re-implements scrolling. The notch is written into the shim's
     * own current wheel event — the {@code eventDWheel} a real GLFW scroll fills —
     * and the screen's own {@code handleMouseInput} is invoked, so the code that
     * moves the list is vanilla's own {@code GuiSlot.handleMouseInput} wheel branch,
     * which adds {@code notches * slotHeight / 2} to {@code amountScrolled}. The
     * state moved is exactly the one {@code GuiListExtended.drawScreen} reads.
     *
     * <p>The one thing the driver supplies is the CURSOR POSITION, and it supplies
     * it because vanilla will not scroll otherwise: that wheel branch sits inside
     * {@code if (isMouseYWithinSlotBounds(this.mouseY))}, and {@code mouseX/mouseY}
     * are set only by {@code drawScreen}. A human satisfies that by moving the mouse
     * over the list before turning the wheel; the driver puts the virtual cursor at
     * the list's centre, which is the same condition, and hands the prior values
     * back afterwards. Without this the scroll would silently depend on wherever the
     * real cursor happened to be — a "works only if you happen to be looking at it"
     * failure that looks like nothing at all from the outside.
     *
     * <p>Vanilla's wheel branch does not clamp; {@code drawScreen} clamps at the
     * start of the next frame through {@code bindAmountScrolled}. That clamp is
     * invoked here too, so the offset this verdict reports is the one that will be
     * drawn rather than an out-of-range intermediate.
     */
    private Driven driveListScroll(GuiScreen screen, String elementId, Gesture g, String before) {
        int listIndex = GuiListReflect.listIndexOf(elementId);
        java.util.List<String> sink = new java.util.ArrayList<>();
        java.util.List<GuiListReflect.ListRef> all = GuiListReflect.lists(screen, sink);
        if (listIndex < 0 || listIndex >= all.size()) {
            return driven(ClickVerdict.NO_ELEMENT, false, "'" + elementId + "' names no list on "
                    + screen.getClass().getSimpleName() + "; this screen has " + all.size()
                    + " list(s), w0..w" + (all.size() - 1),
                    elementId, -1, null, before, before);
        }
        net.minecraft.client.gui.GuiSlot list = all.get(listIndex).list();
        int offsetBefore = list.getAmountScrolled();
        int maxScroll = list.func_148135_f();
        int priorMX = intField(list, "mouseX");
        int priorMY = intField(list, "mouseY");
        int priorWheel = intStaticField(org.lwjgl.input.Mouse.class, "eventDWheel");
        Point inside = GuiListReflect.cursorInside(list);
        int cx = inside.x();
        int cy = inside.y();
        try {
            setIntFieldsQuietly(list, "mouseX", cx, "mouseY", cy);
            // ONE event per detent, because that is what a wheel produces and what
            // vanilla consumes: GuiSlot's branch collapses any event to +/-1 and adds
            // slotHeight/2. Injecting "-3" as a single event would therefore move the
            // list one detent, and a caller asking for three notches would silently
            // get one.
            int step = g.wheelNotches() > 0 ? 1 : -1;
            for (int n = Math.abs(g.wheelNotches()); n > 0; n--) {
                setStaticField(org.lwjgl.input.Mouse.class, "eventDWheel", step);
                invokeHandleMouseInput(screen);
            }
            if (g.wheelNotches() == 0) {
                invokeHandleMouseInput(screen);
            }
        } catch (RuntimeException e) {
            return driven(ClickVerdict.NOT_CONFIRMED, false, "scroll of " + g.wheelNotches()
                    + " notch(es) on " + list.getClass().getSimpleName() + " failed: "
                    + rootMsg(e), elementId, -1, null, before, before);
        } finally {
            setStaticFieldQuietly(org.lwjgl.input.Mouse.class, "eventDWheel", priorWheel);
            setIntFieldsQuietly(list, "mouseX", priorMX, "mouseY", priorMY);
        }
        clampScroll(list);
        String after = svc.fingerprint(screen);
        int offsetAfter = list.getAmountScrolled();
        if (offsetAfter != offsetBefore) {
            return driven(ClickVerdict.CONFIRMED, true, "scrolled " + g.wheelNotches()
                    + " notch(es) on " + list.getClass().getSimpleName() + " (" + elementId + "): "
                    + "the list's own scroll offset moved " + offsetBefore + " -> " + offsetAfter
                    + " of a maximum " + maxScroll
                    + " -- confirmed by re-reading the list's own amountScrolled",
                    elementId, -1, null, before, after);
        }
        return driven(ClickVerdict.NOT_CONFIRMED, false, "scrolled " + g.wheelNotches()
                + " notch(es) on " + list.getClass().getSimpleName() + " (" + elementId
                + "); its scroll offset still reads " + offsetAfter + " of a maximum " + maxScroll
                + (maxScroll == 0
                        ? " -- this list fits entirely on screen, so vanilla has nothing to scroll"
                        : offsetAfter >= maxScroll
                                ? " -- the list is already scrolled to its end"
                                : " -- the wheel reached no handler [UNVERIFIED]"),
                elementId, -1, null, before, after);
    }

    /**
     * Clamp a list's scroll the way {@code GuiSlot.drawScreen} does on the next
     * frame, by invoking vanilla's own {@code bindAmountScrolled}. Doing it now
     * rather than next frame cannot change the resulting state — it is the same
     * method on the same value — but it means the offset reported in this verdict is
     * the one that will be drawn, not an out-of-range intermediate.
     */
    private static void clampScroll(net.minecraft.client.gui.GuiSlot list) {
        try {
            java.lang.reflect.Method m = null;
            for (Class<?> c = list.getClass(); c != null && m == null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod("bindAmountScrolled");
                } catch (NoSuchMethodException ignored) {
                    // walk up
                }
            }
            if (m != null) {
                m.setAccessible(true);
                m.invoke(list);
            }
        } catch (Throwable ignored) {
            // Worst case the offset is reported unclamped; the next draw fixes it.
        }
    }

    /**
     * Write two int fields on a vanilla object, restoring nothing — the caller has
     * already saved the values. Never throws: a list whose cursor fields cannot be
     * written just does not get the virtual-cursor placement.
     */
    private static void setIntFieldsQuietly(Object target, String a, int av, String b, int bv) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(a);
                f.setAccessible(true);
                f.setInt(target, av);
                break;
            } catch (NoSuchFieldException e) {
                // walk up
            } catch (Throwable t) {
                break;
            }
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(b);
                f.setAccessible(true);
                f.setInt(target, bv);
                break;
            } catch (NoSuchFieldException e) {
                // walk up
            } catch (Throwable t) {
                break;
            }
        }
    }

    /**
     * A creative tab is not a button and has no element id, so it is driven as the
     * press+release on the point vanilla's own hit test accepts.
     *
     * <p>Both halves are needed: {@code GuiContainerCreative.mouseClicked}
     * swallows the tab press, and it is {@code mouseReleased} that actually
     * calls {@code setCurrentCreativeTab}. Driving only the press switches
     * nothing at all.
     */
    private Driven driveCreativeTab(GuiScreen screen, String elementId, Gesture g, String before) {
        int want = creativeTabTarget(elementId);
        CreativeTabs tab = CreativeTabs.creativeTabArray[want];
        int tabBefore = creativeTabIndex();
        Point cp = tabPoint(screen, tab);
        if (cp == null) {
            return driven(ClickVerdict.NO_ELEMENT, false, "creative tab " + want
                    + " has no computable click point on this screen", null, -1, null, before, before);
        }
        HeldModifiers held = null;
        try {
            held = HeldModifiers.press(g.modifiers());
            invokeMouseClicked(screen, cp.x(), cp.y(), 0);
            invokeMouseReleased(screen, cp.x(), cp.y(), 0);
        } catch (RuntimeException e) {
            return driven(ClickVerdict.NOT_CONFIRMED, false, "creative tab click failed: "
                    + rootMsg(e), null, -1, null, before, before);
        } finally {
            if (held != null) {
                held.close();
            }
        }
        String after = svc.fingerprint(screen);
        int tabAfter = creativeTabIndex();
        if (tabAfter != tabBefore) {
            return driven(ClickVerdict.CONFIRMED, true, "clicked the creative tab at ("
                    + cp.x() + "," + cp.y() + "): the selected tab moved " + tabBefore + " -> "
                    + tabAfter + " -- confirmed by re-reading the tab index",
                    null, -1, null, before, after);
        }
        return driven(ClickVerdict.NOT_CONFIRMED, false, "clicked the creative tab at ("
                + cp.x() + "," + cp.y() + "); the selected tab still reads " + tabAfter
                + " [UNVERIFIED]", null, -1, null, before, after);
    }

    /**
     * Whether this gesture targets a creative tab.
     *
     * <p>A tab is addressed by its vanilla index in the element id
     * ({@code "tab:6"}), NOT inferred from the geometry: inferring it would
     * route every click on a creative screen to the tab handler, and clicking
     * the tab you are already on is a no-op in vanilla, so "the current tab"
     * would be a gesture that silently does nothing.
     */
    private static boolean isCreativeTabClick(GuiScreen screen, String elementId) {
        return isCreative(screen) && creativeTabTarget(elementId) >= 0;
    }

    /** The tab index an element id addresses, or -1 when it addresses no tab. */
    static int creativeTabTarget(String elementId) {
        if (elementId == null || !elementId.startsWith("tab:")) {
            return -1;
        }
        try {
            int i = Integer.parseInt(elementId.substring(4));
            return (i >= 0 && i < CreativeTabs.creativeTabArray.length) ? i : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * A creative tab's click point, computed from vanilla's own layout
     * arithmetic ({@code GuiContainerCreative.func_147051_a}).
     *
     * <p>Returns null when the layout cannot be computed rather than a guessed
     * pixel: a tab click at the wrong point silently hits a slot instead, which
     * is precisely the failure this class exists to prevent.
     */
    static Point tabPoint(GuiScreen screen, CreativeTabs tab) {
        if (screen == null || tab == null) {
            return null;
        }
        int guiLeft = intField(screen, "guiLeft");
        int guiTop = intField(screen, "guiTop");
        int xSize = intField(screen, "xSize");
        int ySize = intField(screen, "ySize");
        if (guiLeft == 0 && guiTop == 0) {
            return null;
        }
        int col = tab.getTabColumn();
        int l = 28 * col;
        if (col == 5) {
            l = xSize - 28;
        } else if (col > 0) {
            l += col;
        }
        int i1 = tab.isTabInFirstRow() ? guiTop - 28 : guiTop + (ySize - 4);
        return new Point(guiLeft + l + 14, i1 + 16);
    }

    private static boolean isCreative(GuiScreen screen) {
        return screen instanceof net.minecraft.client.gui.inventory.GuiContainerCreative;
    }

    /**
     * The selected creative tab index, read from vanilla's own static field, or
     * -1 when it cannot be read (never a guess).
     */
    static int creativeTabIndex() {
        return intStaticField(net.minecraft.client.gui.inventory.GuiContainerCreative.class,
                "selectedTabIndex");
    }

    /**
     * The screen's scroll offset as a comparable string: the creative list's
     * {@code currentScroll} or a {@code GuiSlot}'s {@code amountScrolled}. Empty
     * when the screen has neither, which is itself the honest answer for a screen
     * that cannot scroll.
     */
    static String scrollOffset(GuiScreen screen) {
        float f = floatField(screen, "currentScroll");
        if (f == Float.NaN) {
            float a = floatField(screen, "amountScrolled");
            return Float.isNaN(a) ? "" : String.valueOf(a);
        }
        return String.valueOf(f);
    }

    /**
     * The mouse button vanilla uses for pick-block: the pick-block KEYCODE plus
     * 100, read from the live game settings.
     *
     * <p>Read rather than hardcoded to 2 on purpose. Vanilla's own arithmetic is
     * {@code mouseButton == keyBindPickBlock.getKeyCode() + 100}, so a player who
     * rebinds pick-block to a letter changes the button that means pick-block.
     * Hardcoding the default would silently degrade a pick-block into a plain
     * click for exactly those players. -1 when it cannot be read, which never
     * equals a real button.
     */
    static int pickBlockButton() {
        Integer code = liveIntField(readGameSettings(), "keyBindPickBlock", "getKeyCode");
        return code == null ? -1 : code + 100;
    }

    /** The live {@code GameSettings}, or null when no client is attached. */
    private static Object readGameSettings() {
        try {
            Minecraft mc = GameBridge.game() == null ? null : GameBridge.game().mc();
            return mc == null ? null : objectField(mc, "gameSettings");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Read an int by calling a no-arg int method, e.g. a {@code KeyBinding}'s
     * {@code getKeyCode()}. Null when the target or method is absent, so an
     * unreadable setting never becomes an invented value.
     */
    private static Integer liveIntField(Object target, String fieldName, String method) {
        if (target == null) {
            return null;
        }
        try {
            Object binding = objectField(target, fieldName);
            if (binding == null) {
                return null;
            }
            Method m = methodInHierarchy(binding.getClass(), method);
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            Object v = m.invoke(binding);
            return v instanceof Number n ? n.intValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The stack the player is CARRYING on the cursor, or {@link GuiStack#EMPTY}.
     *
     * <p>The read-back for a click-outside throw: vanilla applies that drop on
     * the client ({@code Container.slotClick} nulls the cursor), so the cursor
     * going empty is a real observation, not a prediction.
     */
    static GuiStack carriedStack() {
        return playerInventoryStack("getItemStack");
    }

    /**
     * The item in the player's HAND, or {@link GuiStack#EMPTY}. The read-back for
     * pick-block, whose entire effect is that this changes.
     */
    static GuiStack heldItem() {
        return playerInventoryStack("getCurrentItem");
    }

    private static GuiStack playerInventoryStack(String method) {
        try {
            Minecraft mc = GameBridge.game() == null ? null : GameBridge.game().mc();
            if (mc == null) {
                return GuiStack.EMPTY;
            }
            Object player = objectField(mc, "thePlayer");
            if (player == null) {
                return GuiStack.EMPTY;
            }
            Object inv = objectField(player, "inventory");
            if (inv == null) {
                return GuiStack.EMPTY;
            }
            Method m = methodInHierarchy(inv.getClass(), method);
            if (m == null) {
                return GuiStack.EMPTY;
            }
            m.setAccessible(true);
            Object v = m.invoke(inv);
            return v instanceof net.minecraft.item.ItemStack st ? GuiStack.of(st) : GuiStack.EMPTY;
        } catch (Throwable t) {
            return GuiStack.EMPTY;
        }
    }

    /** The container slot at a scaled-GUI point, or null. */
    static Slot slotAt(GuiScreen screen, Point p) {
        if (!(screen instanceof GuiContainer gc) || gc.inventorySlots == null || p == null) {
            return null;
        }
        // guiLeft/guiTop are protected on GuiContainer and this class is not a
        // subclass, so they are read reflectively like every other vanilla field.
        int left = intField(screen, "guiLeft");
        int top = intField(screen, "guiTop");
        for (Slot s : gc.inventorySlots.inventorySlots) {
            if (s == null) {
                continue;
            }
            if (p.x() >= left + s.xDisplayPosition && p.x() < left + s.xDisplayPosition + 16
                    && p.y() >= top + s.yDisplayPosition && p.y() < top + s.yDisplayPosition + 16) {
                return s;
            }
        }
        return null;
    }

    /** A human-readable name for the gesture, for the trajectory and the message. */
    private static String describe(Gesture g) {
        if (g.wheelNotches() != 0) {
            return "scroll of " + g.wheelNotches() + " notch(es)";
        }
        if (!g.dragPath().isEmpty()) {
            return "drag-split";
        }
        if (g.repeatPresses() > 1) {
            return "double-click";
        }
        if (g.modifiers().contains(Modifier.SHIFT)) {
            return "shift-click";
        }
        return g.button() == 1 ? "right-click" : "left-click";
    }

    private static String elementId(GuiElement el) {
        return el == null ? "?" : el.id();
    }

    /** Read a private float field by name; NaN when absent, never a guessed 0. */
    private static float floatField(Object target, String name) {
        if (target == null) {
            return Float.NaN;
        }
        try {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(target);
                    return v instanceof Number n ? n.floatValue() : Float.NaN;
                } catch (NoSuchFieldException ignored) {
                    // walk up
                }
            }
        } catch (Throwable ignored) {
            // an unreadable field is reported as "no value", never as 0
        }
        return Float.NaN;
    }

    /** Read a private static int field by name; 0 when absent. */
    private static int intStaticField(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(null);
            return v instanceof Number n ? n.intValue() : 0;
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /**
     * Write a private static int field, failing loudly: a swallowed write here
     * would mean reporting a scroll that never reached the screen.
     */
    private static void setStaticField(Class<?> owner, String name, int value) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot write " + owner.getSimpleName() + "." + name
                    + "; the input event would not reach the screen", e);
        }
    }

    /** Restore-on-exit variant: never masks the failure being reported. */
    private static void setStaticFieldQuietly(Class<?> owner, String name, int value) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(null, value);
        } catch (ReflectiveOperationException ignored) {
            // best effort: the write being undone is a restore, not the action
        }
    }

    /** Read a private object field by name, walking the hierarchy; null if absent. */
    private static Object objectField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // walk up
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Wait for the server's answer to a slot click and re-read the slot.
     *
     * <p>The wait is not politeness. The server applies the click to ITS container
     * and pushes every slot back over C0F; until that arrives the client can still
     * be showing the pre-click state, so an immediate read reports "nothing moved"
     * for a click that worked. A click that has not changed within the budget is
     * reported as NOT_CONFIRMED and as an error, because for a slot there IS a
     * server round trip to observe and it did not happen.
     */
    private ClickResult confirmSlot(Driven d) {
        int slot = d.slotNumber();
        GuiStack last = d.before();
        String window = windowOf(liveScreenQuiet());
        long deadline = System.nanoTime() + CONFIRM_BUDGET_MS * 1_000_000L;
        while (true) {
            try {
                last = GameBridge.onGameThread(() -> readSlot(liveScreen(), slot), TIMEOUT_MS);
            } catch (Exception e) {
                return new ClickResult(ClickVerdict.UNREADABLE, false, "clicked slot " + slot
                        + " but could not read it back (" + rootMsg(e)
                        + "); [UNVERIFIED] — nothing is claimed about whether the server accepted it");
            }
            if (!Objects.equals(d.before(), last)) {
                return new ClickResult(ClickVerdict.CONFIRMED, true, d.message()
                        + "; slot " + slot + " now reads " + last
                        + " -- confirmed by re-reading the slot, not by the click having been sent");
            }
            if (System.nanoTime() >= deadline) {
                return new ClickResult(ClickVerdict.NOT_CONFIRMED, false, "clicked slot " + slot
                        + ", but " + CONFIRM_BUDGET_MS + "ms later it still reads " + last
                        + ". Either the server rejected the click (this slot will not take that "
                        + "item, or the slot index belongs to a different window) or window "
                        + window + " is not the window the server has open. [UNVERIFIED]");
            }
            try {
                Thread.sleep(CONFIRM_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ClickResult(ClickVerdict.NOT_CONFIRMED, false, "interrupted while "
                        + "confirming the click on slot " + slot + "; the click was driven and the "
                        + "slot still reads " + last + " [UNVERIFIED]");
            }
        }
    }

    /**
     * Wait for a probed slot to differ from {@code before}. Pure over its two
     * collaborators, so the wait is testable with no game and no wall clock.
     *
     * @return {@link ClickVerdict#CONFIRMED} as soon as a read differs,
     *         {@link ClickVerdict#UNREADABLE} if the probe throws,
     *         {@link ClickVerdict#NOT_CONFIRMED} once the budget passes
     */
    static ClickVerdict awaitChange(GuiStack before, SlotProbe probe, Sleeper sleeper,
                                    long budgetMillis) {
        long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
        do {
            GuiStack now;
            try {
                now = probe.read();
            } catch (RuntimeException e) {
                return ClickVerdict.UNREADABLE;
            }
            if (!Objects.equals(before, now)) {
                return ClickVerdict.CONFIRMED;
            }
            sleeper.sleep(CONFIRM_POLL_MS);
        } while (System.nanoTime() < deadline);
        return ClickVerdict.NOT_CONFIRMED;
    }

    /**
     * Whether clicking at {@code cp} would throw the carried stack into the world.
     *
     * <p>Vanilla's rule, not a guess: {@code GuiContainer.mouseClicked:380-383}
     * rewrites any click outside {@code [guiLeft, guiLeft+xSize) x [guiTop,
     * guiTop+ySize)} to {@code slotId = -999}, and {@code
     * Container.slotClick:234-253} answers {@code slotId == -999} with
     * {@code dropPlayerItemWithRandomChoice}. The hit test is vanilla's own
     * {@code isPointInRegion} (GuiContainer:666) rather than a reimplementation, so
     * the guard cannot drift from the behaviour it is guarding.
     *
     * <p>This is the ONLY thing this surface refuses for destructiveness. It never
     * sends clickType 4 (drop from a slot, Container:446-455), never a shift
     * quick-move, never the pick-block key and never a drag, because the surface has
     * no verb for any of them: it drives {@code mouseClicked(x, y, 0|1)} and that
     * is all. The destructive verbs are absent by construction, not by refusal.
     */
    static boolean dropsCarriedStack(GuiScreen screen, Point cp) {
        if (!(screen instanceof GuiContainer gc) || cp == null) {
            return false;
        }
        try {
            Method m = methodInHierarchy(gc.getClass(), "isPointInRegion",
                    int.class, int.class, int.class, int.class, int.class, int.class);
            // xSize/ySize are protected on GuiContainer and this class is not a
            // subclass, so they are read reflectively like every other vanilla field.
            int xSize = intField(gc, "xSize");
            int ySize = intField(gc, "ySize");
            if (m == null || xSize <= 0 || ySize <= 0) {
                return false;
            }
            m.setAccessible(true);
            // 0,0,xSize,ySize is the panel rectangle itself.
            return !(Boolean) m.invoke(gc, 0, 0, xSize, ySize, cp.x(), cp.y());
        } catch (Throwable t) {
            return false; // an unreadable panel must not become an invented refusal
        }
    }

    /** Read a protected vanilla int field by name, or 0 when it is absent. */
    private static int intField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(target);
                if (v instanceof Number n) {
                    return n.intValue();
                }
            } catch (NoSuchFieldException ignored) {
                // walk up
            } catch (Throwable ignored) {
                return 0;
            }
        }
        return 0;
    }

    // ===== TYPE / PRESS =====

    /**
     * Type {@code text} into the text-field element (focusing it first via a
     * click), optionally clearing it first. Uses {@code textboxKeyTyped}/{@code
     * setText} on the resolved {@code GuiTextField}.
     */
    public Result typeText(int epoch, String fingerprint, String elementId, String text,
                           boolean clearFirst) throws Exception {
        return GameBridge.onGameThread(
                () -> typeTextOnScreen(liveScreen(), epoch, fingerprint, elementId, text, clearFirst),
                TIMEOUT_MS);
    }

    /** Pure type body against a supplied screen; records into the trajectory. */
    Result typeTextOnScreen(GuiScreen screen, int epoch, String fingerprint, String elementId,
                            String text, boolean clearFirst) {
        String before = svc.fingerprint(screen);
        String guardFail = guardMessage(screen, epoch, fingerprint);
        if (guardFail != null) {
            return recordText(GuiTrajectory.KIND_TYPE, elementId, Result.fail(guardFail), before, before);
        }
        GuiElement el = resolve(screen, elementId);
        if (el == null || !GuiElement.KIND_TEXTFIELD.equals(el.kind())) {
            Result r = Result.fail("no text field '" + elementId + "' on the current screen; "
                    + "call gui_snapshot again");
            return recordText(GuiTrajectory.KIND_TYPE, elementId, r, before, svc.fingerprint(screen));
        }
        Object field = resolveTextField(screen, elementId);
        if (field == null) {
            Result r = Result.fail("could not resolve the live text field for '" + elementId + "'");
            return recordText(GuiTrajectory.KIND_TYPE, elementId, r, before, svc.fingerprint(screen));
        }
        // Focus it by clicking its center, then drive the field's own handlers.
        Point cp = el.clickPoint();
        if (cp != null) {
            invokeMouseClicked(screen, cp.x(), cp.y(), 0);
        }
        if (clearFirst) {
            invoke(field, "setText", new Class<?>[] {String.class}, "");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            invoke(field, "textboxKeyTyped", new Class<?>[] {char.class, int.class}, c, 0);
        }
        String now = (String) invoke(field, "getText", new Class<?>[] {});
        Result r = Result.ok("typed into " + elementId + "; field text is now '" + now + "'");
        return recordText(GuiTrajectory.KIND_TYPE, elementId, r, before, svc.fingerprint(screen));
    }

    /**
     * Press a key on the current screen (e.g. Escape to close, Return to confirm)
     * by invoking {@code GuiScreen.keyTyped(char, keyCode)}.
     */
    public Result pressKey(int epoch, String fingerprint, char ch, int keyCode) throws Exception {
        return GameBridge.onGameThread(() -> pressKeyOnScreen(liveScreen(), epoch, fingerprint, ch, keyCode),
                TIMEOUT_MS);
    }

    /** Pure press body against a supplied screen; records into the trajectory. */
    Result pressKeyOnScreen(GuiScreen screen, int epoch, String fingerprint, char ch, int keyCode) {
        String before = svc.fingerprint(screen);
        String keyId = "key:" + keyCode + (ch != 0 ? "('" + ch + "')" : "");
        String guardFail = guardMessage(screen, epoch, fingerprint);
        if (guardFail != null) {
            return recordText(GuiTrajectory.KIND_PRESS, keyId, Result.fail(guardFail), before, before);
        }
        invokeKeyTyped(screen, ch, keyCode);
        Result r = Result.ok("pressed key code " + keyCode
                + (ch != 0 ? " ('" + ch + "')" : "") + " on " + screen.getClass().getSimpleName()
                + "; a key press has no server round trip of its own, so nothing about its "
                + "outcome is claimed [UNVERIFIED]");
        return recordText(GuiTrajectory.KIND_PRESS, keyId, r, before, svc.fingerprint(screen));
    }

    // ===== internals =====

    private ClickResult record(Driven d, ClickResult r) {
        if (trajectory != null) {
            trajectory.record(GuiTrajectory.KIND_CLICK,
                    d.elementId() == null ? "" : d.elementId(), r.ok(), r.message(),
                    d.beforeFingerprint(), d.afterFingerprint());
        }
        return r;
    }

    private Result recordText(String kind, String elementId, Result r, String before, String after) {
        if (trajectory != null) {
            trajectory.record(kind, elementId, r.ok(), r.message(), before, after);
        }
        return r;
    }

    private static Driven driven(ClickVerdict v, boolean ok, String message, String elementId,
                                 int slotNumber, GuiStack before, String beforeFp, String afterFp) {
        return new Driven(v, ok, message, elementId, slotNumber, before, beforeFp, afterFp);
    }

    private static boolean wasDriven(ClickVerdict v) {
        return v == ClickVerdict.CONFIRMED || v == ClickVerdict.NOT_CONFIRMED;
    }

    private static int slotNumberOf(GuiElement el) {
        Object n = el.attributes().get("slotNumber");
        return n instanceof Integer i ? i : -1;
    }

    private static String panelName(GuiScreen screen) {
        return screen instanceof GuiContainer
                ? "container panel" : screen.getClass().getSimpleName();
    }

    private static String windowOf(GuiScreen screen) {
        Container c = (screen instanceof GuiContainer gc) ? gc.inventorySlots : null;
        return c == null ? "?" : String.valueOf(c.windowId);
    }

    /** The live screen, tolerating a bridge that is not up; only used in messages. */
    private GuiScreen liveScreenQuiet() {
        try {
            return liveScreen();
        } catch (Throwable t) {
            return null;
        }
    }

    private GuiScreen liveScreen() {
        Minecraft mc = game.mc();
        return mc == null ? null : mc.currentScreen;
    }

    /**
     * Why the reference is dead, or null when it is live. Split from the Result
     * form because the click path needs a verdict while type/press need a message.
     */
    private String guardMessage(GuiScreen screen, int epoch, String fingerprint) {
        if (screen == null) {
            return "no GUI screen is open now (it closed since the snapshot); "
                    + "call gui_snapshot again";
        }
        if (!svc.validateAgainst(screen, epoch, fingerprint)) {
            return "screen changed since epoch " + epoch + " — now "
                    + svc.fingerprint(screen) + "; call gui_snapshot again before acting";
        }
        return null;
    }

    /** One container slot's contents, read on the game thread. Empty for a bad index. */
    static GuiStack readSlot(GuiScreen screen, int slotNumber) {
        if (!(screen instanceof GuiContainer gc) || gc.inventorySlots == null
                || slotNumber < 0 || slotNumber >= gc.inventorySlots.inventorySlots.size()) {
            return GuiStack.EMPTY;
        }
        Slot slot = gc.inventorySlots.getSlot(slotNumber);
        return slot == null ? GuiStack.EMPTY : GuiStack.of(slot.getStack());
    }

    private static GuiElement resolve(GuiScreen screen, String elementId) {
        for (GuiElement el : GuiReflect.extract(screen, false).elements()) {
            if (el.id().equals(elementId)) {
                return el;
            }
        }
        return null;
    }

    /**
     * Resolve the live {@code GuiTextField} object for a {@code t{idx}} element id,
     * mirroring GuiReflect.extractTextFields' declaration-order walk up the screen's
     * class hierarchy so the index matches the snapshot exactly.
     */
    private static Object resolveTextField(GuiScreen screen, String elementId) {
        if (elementId == null || !elementId.startsWith("t")) {
            return null;
        }
        final int want;
        try {
            want = Integer.parseInt(elementId.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
        int idx = 0;
        for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!net.minecraft.client.gui.GuiTextField.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                Object tf;
                try {
                    f.setAccessible(true);
                    tf = f.get(screen);
                } catch (Throwable t) {
                    continue;
                }
                if (tf == null) {
                    continue; // matches GuiReflect: nulls don't consume an index
                }
                if (idx == want) {
                    return tf;
                }
                idx++;
            }
        }
        return null;
    }

    private static void invokeMouseClicked(GuiScreen screen, int x, int y, int button) {
        Method m = methodInHierarchy(screen.getClass(), "mouseClicked",
                int.class, int.class, int.class);
        if (m == null) {
            throw new IllegalStateException("mouseClicked(int,int,int) not found on "
                    + screen.getClass().getName());
        }
        try {
            m.setAccessible(true);
            m.invoke(screen, x, y, button);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("mouseClicked failed: "
                    + (e.getCause() != null ? e.getCause() : e), e);
        }
    }

    /**
     * The release half of a click. In vanilla this is where modes 5 (drag-split)
     * and 6 (double-click collect) are actually produced, so a "click" that never
     * calls it cannot reach them at all.
     */
    private static void invokeMouseReleased(GuiScreen screen, int x, int y, int button) {
        invokeVoid(screen, "mouseReleased", new Class<?>[] {int.class, int.class, int.class},
                "mouseReleased", x, y, button);
    }

    /**
     * A drag sweep. {@code GuiContainer.mouseClickMove} is what accumulates the
     * slots a drag-split will distribute, so without it the release distributes
     * nothing.
     */
    private static void invokeMouseClickMove(GuiScreen screen, int x, int y, int button, long held) {
        invokeVoid(screen, "mouseClickMove",
                new Class<?>[] {int.class, int.class, int.class, long.class},
                "mouseClickMove", x, y, button, held);
    }

    /**
     * The screen's own mouse-event entry point, which is the ONLY place the
     * scroll wheel is read ({@code Mouse.getEventDWheel()}). Invoking it rather
     * than a reimplementation means whichever consumer vanilla wrote for this
     * screen is the one that runs.
     */
    private static void invokeHandleMouseInput(GuiScreen screen) {
        invokeVoid(screen, "handleMouseInput", new Class<?>[] {}, "handleMouseInput");
    }

    private static void invokeVoid(GuiScreen screen, String method, Class<?>[] params,
                                   String label, Object... args) {
        Method m = methodInHierarchy(screen.getClass(), method, params);
        if (m == null) {
            throw new IllegalStateException(label + " not found on " + screen.getClass().getName());
        }
        try {
            m.setAccessible(true);
            m.invoke(screen, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(label + " failed: "
                    + (e.getCause() != null ? e.getCause() : e), e);
        }
    }

    private static void invokeKeyTyped(GuiScreen screen, char ch, int keyCode) {
        Method m = methodInHierarchy(screen.getClass(), "keyTyped", char.class, int.class);
        if (m == null) {
            throw new IllegalStateException("keyTyped(char,int) not found on "
                    + screen.getClass().getName());
        }
        try {
            m.setAccessible(true);
            m.invoke(screen, ch, keyCode);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("keyTyped failed: "
                    + (e.getCause() != null ? e.getCause() : e), e);
        }
    }

    /** Find a (possibly protected/inherited) method by walking up the hierarchy. */
    private static Method methodInHierarchy(Class<?> start, String name, Class<?>... params) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                // walk up
            }
        }
        return null;
    }

    private static Object invoke(Object target, String name, Class<?>[] params, Object... args) {
        Method m = methodInHierarchy(target.getClass(), name, params);
        if (m == null) {
            throw new IllegalStateException(name + " not found on " + target.getClass().getName());
        }
        try {
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(name + " failed: "
                    + (e.getCause() != null ? e.getCause() : e), e);
        }
    }

    /**
     * The deepest cause's own words.
     *
     * <p>Unwraps the WHOLE chain, not one level. A reflective call that throws
     * arrives as {@code RuntimeException -> InvocationTargetException -> the real
     * failure}, and stopping after one level reports "InvocationTargetException" —
     * which names the driver's own plumbing and tells the reader nothing about what
     * actually went wrong inside vanilla.
     */
    private static String rootMsg(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() != null ? c.getMessage() : c.toString();
    }
}
