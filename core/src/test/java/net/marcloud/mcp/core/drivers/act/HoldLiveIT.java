package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.GameAccess;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * LIVE scaffold (default SKIPPED). Requires a running Minecraft client with the player in a world
 * holding something with a use duration. Gated behind {@code -Dmcp.it.live=true}; without it every
 * test {@link Assume#assumeTrue assume-skips} and never fails.
 *
 * <p>Mirrors {@code DigLiveIT}/{@code InteractLiveIT}. This one carries more weight than those,
 * because the hold channel's mechanism is entirely live: {@code KeyBinding.setKeyBindState} writing a
 * static hash, vanilla's own {@code Minecraft.java:2118-2122} branch reading it back, and the
 * server's status id 9 arriving to end a meal. The headless {@code HoldControllerTest} exercises the
 * controller against a MODEL of those rules ({@code FakeActuator.advanceGameTick}); whether the model
 * matches the real client is exactly what these shells are for.
 *
 * <p>What is worth checking here, in order of how much it would cost to be wrong:
 *
 * <ol>
 *   <li>Does the assertion take at all -- {@code holdUseKey()} true, and {@code useKeyHeld()} true
 *       on the following tick without another write.
 *   <li>Does eating actually finish, with food rising, rather than the count falling 32 → 0 the way
 *       it did before this channel existed.
 *   <li>Does exactly ONE item get consumed. Vanilla restarts a use while the key is down
 *       ({@code Minecraft.java:2158}), so a release that lands one tick late eats two.
 *   <li>Does a bow fire on release, and does a sub-{@link HoldController#BOW_MIN_CHARGE_TICKS} draw
 *       fire nothing.
 *   <li>Does a chat window mid-hold end the hold FAILED (the {@code unPressAllKeys} path) rather than
 *       reporting a hold that has already stopped.
 * </ol>
 *
 * <p>Run live with: {@code ./mvnw -pl core test -Dtest=HoldLiveIT -Dmcp.it.live=true}
 */
public class HoldLiveIT {

    private static final boolean LIVE = Boolean.getBoolean("mcp.it.live");

    private static void requireLive() {
        Assume.assumeTrue("requires live game window; run with -Dmcp.it.live=true", LIVE);
    }

    private static void requireInWorld(ActActuator act) {
        boolean up;
        try {
            up = act.inWorld();
        } catch (Throwable noGame) {
            up = false;
        }
        Assume.assumeTrue("requires the player to be in a world", up);
    }

    /**
     * The seam itself, isolated from any controller.
     *
     * <p>Worth its own test because it is the one step no headless test can speak to: the fake's key
     * state is a field, while the real one is a private field inside a binding looked up from a static
     * {@code IntHashMap} by keyCode. If the lookup misses, {@code setKeyBindState} silently does
     * nothing and every hold above it is built on sand.
     */
    @Test
    public void theUseKeyCanBeAssertedAndReadBack() {
        requireLive();
        ActActuator act = new LivePlayerActuator(new GameAccess());
        requireInWorld(act);

        boolean took = act.holdUseKey();
        boolean readsHeld = act.useKeyHeld();
        act.releaseUseKey();
        assertTrue("holdUseKey must report that the write took, and the binding must read back as "
                + "held; a false here means the keyCode is not in KeyBinding's static hash",
                took && readsHeld);
        assertTrue("and a release must read back as up", !act.useKeyHeld());
    }

    /**
     * Hold whatever is in hand until vanilla ends it.
     *
     * <p>Asserts only that it TERMINATED, not that it succeeded: with an empty hand or full hunger
     * the honest outcome is a failure, and the point of the shell is that the loop ends either way
     * rather than holding a key forever. The message is what a human reads.
     */
    @Test
    public void holdUntilDoneTerminatesOnTheLiveClient() {
        requireLive();
        ActActuator act = new LivePlayerActuator(new GameAccess());
        requireInWorld(act);

        HoldController c = new HoldController(InteractIntent.holdUntilDone());
        ActOutcome out = null;
        for (int i = 0; i < 200 && (out == null || !out.terminal()); i++) {
            out = c.tick(act);
        }
        if (out == null || !out.terminal()) {
            act.releaseUseKey();
        }
        assertTrue("live hold-until-done should terminate: " + (out == null ? "null" : out.message()),
                out != null && out.terminal());
        assertTrue("and must not leave vanilla's use key asserted", !act.useKeyHeld());
    }

    /**
     * Draw for full charge, then release.
     *
     * <p>{@link HoldController#BOW_FULL_CHARGE_TICKS} ticks because that is where vanilla's charge
     * formula reaches 1.0. Ticked in a tight loop rather than once per game tick, so the DRAW COUNT
     * this reports is the loop's, not vanilla's -- treat the tick numbers as indicative and the
     * termination as the assertion.
     */
    @Test
    public void holdThenReleaseTerminatesOnTheLiveClient() {
        requireLive();
        ActActuator act = new LivePlayerActuator(new GameAccess());
        requireInWorld(act);

        HoldController c = new HoldController(
                InteractIntent.holdThenRelease(HoldController.BOW_FULL_CHARGE_TICKS));
        ActOutcome out = null;
        for (int i = 0; i < 200 && (out == null || !out.terminal()); i++) {
            out = c.tick(act);
        }
        if (out == null || !out.terminal()) {
            act.releaseUseKey();
        }
        assertTrue("live hold-then-release should terminate: "
                + (out == null ? "null" : out.message()), out != null && out.terminal());
        assertTrue("and must not leave vanilla's use key asserted", !act.useKeyHeld());
    }
}
