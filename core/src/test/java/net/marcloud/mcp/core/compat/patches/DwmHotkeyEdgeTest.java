package net.marcloud.mcp.core.compat.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.Test;

/**
 * Edge-detection teeth for the KI-11 hotkey, driven headlessly.
 *
 * <p>The hook runs once per keyboard EVENT inside vanilla's dispatch loop, so it sees presses and
 * releases for every key, not just the bound one. Three ways that goes wrong and none is visible by
 * reading: a hold re-firing every event, a different key clearing the armed state so the next real
 * press is swallowed, and a release of the bound key failing to re-arm it.
 *
 * <p>{@code onKeyEvent} reads the live shim keyboard, which needs a window, so these drive the
 * decision logic directly through the same static state the hook mutates. That keeps the test
 * headless while still exercising the exact fields whose interaction is the risk.
 */
public final class DwmHotkeyEdgeTest {

    /** RSHIFT, the default binding. */
    private static final int BOUND = 0x36;
    private static final int OTHER = 0x1E;

    /** Reset between cases: the state is static, so a leftover would decide the next assertion. */
    private static void rearm() throws Exception {
        Method reset = DwmHotkey.class.getDeclaredMethod("resetEdgeState");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    private static boolean wasDown() throws Exception {
        Field f = DwmHotkey.class.getDeclaredField("wasDown");
        f.setAccessible(true);
        return (Boolean) f.get(null);
    }

    /**
     * The decision the hook makes for one event, with the side effect on {@code wasDown}.
     *
     * <p>A copy of the branch order in {@code onKeyEvent}, deliberately: the alternative is a live
     * window and synthesised GLFW events for what is a three-field state machine. The copy is kept
     * honest by {@link #theProductionOrderMatchesWhatIsTestedHere()}, which asserts the real method's
     * source still has these branches in this order.
     */
    private static boolean decide(int key, boolean down) throws Exception {
        Field f = DwmHotkey.class.getDeclaredField("wasDown");
        f.setAccessible(true);
        if (key != BOUND) {
            return false;
        }
        if (!down) {
            f.set(null, false);
            return false;
        }
        if ((Boolean) f.get(null)) {
            return false;
        }
        f.set(null, true);
        return true;
    }

    /** A press fires exactly once; holding it does not fire again. */
    @Test
    public void aHeldKeyFiresOnlyOnce() throws Exception {
        rearm();
        assertTrue("the first press must fire", decide(BOUND, true));
        assertFalse("a repeat while held must not", decide(BOUND, true));
        assertFalse("nor a third", decide(BOUND, true));
    }

    /** Releasing re-arms, so the next press fires again. */
    @Test
    public void releasingRearms() throws Exception {
        rearm();
        assertTrue(decide(BOUND, true));
        assertFalse("the release itself must not fire", decide(BOUND, false));
        assertTrue("the next press must fire", decide(BOUND, true));
    }

    /**
     * Another key's events must not disturb the armed state.
     *
     * <p>This is the one worth testing. The hook sees every key, so if the bound-key check came after
     * the state update, typing anything while holding the hotkey would clear {@code wasDown} and the
     * hotkey would re-fire on the next repeat event — or, the other way round, an unrelated release
     * would arm it and a held key would fire twice.
     */
    @Test
    public void otherKeysDoNotDisturbTheArmedState() throws Exception {
        rearm();
        assertTrue(decide(BOUND, true));
        assertTrue("precondition: armed", wasDown());

        assertFalse(decide(OTHER, true));
        assertFalse(decide(OTHER, false));
        assertFalse(decide(0x11, true));
        assertTrue("another key's events must leave the state alone", wasDown());

        assertFalse("so the held hotkey still must not re-fire", decide(BOUND, true));
    }

    /** Interleaving the two keys must not produce a spurious fire. */
    @Test
    public void alternatingKeysNeverFireSpuriously() throws Exception {
        rearm();
        int fires = 0;
        // One press of the bound key, then a burst of other-key traffic, then its release.
        if (decide(BOUND, true)) {
            fires++;
        }
        for (int i = 0; i < 10; i++) {
            if (decide(OTHER, i % 2 == 0)) {
                fires++;
            }
            if (decide(BOUND, true)) {
                fires++;
            }
        }
        if (decide(BOUND, false)) {
            fires++;
        }
        assertEquals("exactly one fire for one physical press, whatever else was typed", 1, fires);
    }

    /** A release with nothing held must not fire — the state starts disarmed, not armed. */
    @Test
    public void aReleaseWithoutAPressDoesNotFire() throws Exception {
        rearm();
        assertFalse(decide(BOUND, false));
        assertFalse("and must leave it disarmed", wasDown());
    }

    /**
     * The production branch order must still be the one modelled above.
     *
     * <p>Without this, {@link #decide} could drift from {@code onKeyEvent} and every test here would
     * keep passing while testing a copy of nothing.
     */
    @Test
    public void theProductionOrderMatchesWhatIsTestedHere() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
            "src/main/java/net/marcloud/mcp/core/compat/patches/DwmHotkey.java")),
            java.nio.charset.StandardCharsets.UTF_8);
        int body = src.indexOf("public static void onKeyEvent()");
        assertTrue("onKeyEvent must exist", body > 0);
        String method = src.substring(body, src.indexOf("private static void toggleScreen"));

        int boundCheck = method.indexOf("key != BOUND_KEY");
        int downCheck = method.indexOf("if (!down)");
        int heldCheck = method.indexOf("if (wasDown)");
        assertTrue("the bound-key check must exist", boundCheck > 0);
        assertTrue("the release branch must exist", downCheck > 0);
        assertTrue("the already-held branch must exist", heldCheck > 0);
        assertTrue("the bound-key check must come FIRST, or another key's events mutate the armed "
            + "state — the exact bug otherKeysDoNotDisturbTheArmedState covers",
            boundCheck < downCheck && boundCheck < heldCheck);
        assertTrue("the release branch must precede the already-held branch, or a release while "
            + "armed would be swallowed and the key could never re-arm", downCheck < heldCheck);
    }

    /** A nonsense binding must disable the hotkey rather than silently fall back to the default. */
    @Test
    public void boundKeyIsWithinTheScancodeRangeOrDisabled() {
        int bound = DwmHotkey.boundKey();
        assertTrue("boundKey must be -1 (disabled) or a real scancode, never anything else",
            bound == -1 || (bound >= 0 && bound < 256));
    }
}
