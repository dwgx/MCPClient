package net.marcloud.mcp.core.gui;

import java.lang.reflect.Method;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.GameBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/**
 * Drives the REAL vanilla GUI handlers for the {@code gui_*} action tools. The
 * LLM never sends pixels — it sends an element id from a {@link GuiSnapshot}, and
 * this class re-resolves that id against the LIVE screen on the game thread,
 * recomputes the authoritative click-point from current geometry, and invokes the
 * genuine {@link GuiScreen#mouseClicked}/{@code keyTyped} (both protected in
 * vanilla 1.8.9, reached via reflection). All actions run on the game thread.
 *
 * <p><b>Stale-action guard.</b> Every action carries the {@code epoch} +
 * {@code fingerprint} the snapshot was taken at; if the live screen changed
 * underneath (identity or structure), the action is rejected with a loud,
 * actionable message rather than clicking the wrong thing.
 *
 * <p>Coordinates are scaled-GUI space (what {@code mouseClicked} consumes), so no
 * framebuffer/DPI conversion is ever traversed for an action — the class of bug
 * that plagues pixel-grounded computer-use agents cannot occur here.
 */
public final class GuiActions {

    private static final long TIMEOUT_MS = 3000L;

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
     * Click the element with {@code elementId} using {@code button} (0=left,
     * 1=right), after validating the snapshot is not stale. Re-resolves the
     * element's live click-point and invokes the real {@code mouseClicked}.
     */
    public Result click(int epoch, String fingerprint, String elementId, int button) throws Exception {
        return GameBridge.onGameThread(() -> clickOnScreen(liveScreen(), epoch, fingerprint, elementId, button),
                TIMEOUT_MS);
    }

    /**
     * Pure click body against an (already-resolved) screen — the seam tests drive
     * headless. Records the action into the trajectory with the screen fingerprint
     * captured immediately before and after driving the handler.
     */
    Result clickOnScreen(GuiScreen screen, int epoch, String fingerprint, String elementId, int button) {
        String before = svc.fingerprint(screen);
        Result guard = guardStale(screen, epoch, fingerprint);
        if (guard != null) {
            return record(GuiTrajectory.KIND_CLICK, elementId, guard, before, before);
        }
        GuiElement el = resolve(screen, elementId);
        if (el == null) {
            Result r = Result.fail("no element '" + elementId + "' on the current screen; "
                    + "call gui_snapshot again");
            return record(GuiTrajectory.KIND_CLICK, elementId, r, before, svc.fingerprint(screen));
        }
        Point cp = el.clickPoint();
        if (cp == null) {
            Result r = Result.fail("element '" + elementId + "' has no click-point");
            return record(GuiTrajectory.KIND_CLICK, elementId, r, before, svc.fingerprint(screen));
        }
        invokeMouseClicked(screen, cp.x(), cp.y(), button);
        Result r = Result.ok("clicked " + elementId + " ('" + el.name() + "') with button " + button
                + " at (" + cp.x() + "," + cp.y() + ")");
        return record(GuiTrajectory.KIND_CLICK, elementId, r, before, svc.fingerprint(screen));
    }

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
        Result guard = guardStale(screen, epoch, fingerprint);
        if (guard != null) {
            return record(GuiTrajectory.KIND_TYPE, elementId, guard, before, before);
        }
        GuiElement el = resolve(screen, elementId);
        if (el == null || !GuiElement.KIND_TEXTFIELD.equals(el.kind())) {
            Result r = Result.fail("no text field '" + elementId + "' on the current screen; "
                    + "call gui_snapshot again");
            return record(GuiTrajectory.KIND_TYPE, elementId, r, before, svc.fingerprint(screen));
        }
        Object field = resolveTextField(screen, elementId);
        if (field == null) {
            Result r = Result.fail("could not resolve the live text field for '" + elementId + "'");
            return record(GuiTrajectory.KIND_TYPE, elementId, r, before, svc.fingerprint(screen));
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
        return record(GuiTrajectory.KIND_TYPE, elementId, r, before, svc.fingerprint(screen));
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
        Result guard = guardStale(screen, epoch, fingerprint);
        if (guard != null) {
            return record(GuiTrajectory.KIND_PRESS, keyId, guard, before, before);
        }
        invokeKeyTyped(screen, ch, keyCode);
        Result r = Result.ok("pressed key code " + keyCode
                + (ch != 0 ? " ('" + ch + "')" : "") + " on " + screen.getClass().getSimpleName());
        return record(GuiTrajectory.KIND_PRESS, keyId, r, before, svc.fingerprint(screen));
    }

    // ===== internals =====

    /** Log the action into the trajectory (if present) and return the Result unchanged. */
    private Result record(String kind, String elementId, Result r, String before, String after) {
        if (trajectory != null) {
            trajectory.record(kind, elementId, r.ok(), r.message(), before, after);
        }
        return r;
    }

    private GuiScreen liveScreen() {
        Minecraft mc = game.mc();
        return mc == null ? null : mc.currentScreen;
    }

    /** Returns a fail Result if the screen is gone or the snapshot is stale, else null. */
    private Result guardStale(GuiScreen screen, int epoch, String fingerprint) {
        if (screen == null) {
            return Result.fail("no GUI screen is open now (it closed since the snapshot); "
                    + "call gui_snapshot again");
        }
        if (!svc.validateAgainst(screen, epoch, fingerprint)) {
            return Result.fail("screen changed since epoch " + epoch + " — now "
                    + svc.fingerprint(screen) + "; call gui_snapshot again before acting");
        }
        return null;
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
        Method m = protectedMethod(screen.getClass(), "mouseClicked",
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

    private static void invokeKeyTyped(GuiScreen screen, char ch, int keyCode) {
        Method m = protectedMethod(screen.getClass(), "keyTyped", char.class, int.class);
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
    private static Method protectedMethod(Class<?> start, String name, Class<?>... params) {
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
        Method m = protectedMethod(target.getClass(), name, params);
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
}
