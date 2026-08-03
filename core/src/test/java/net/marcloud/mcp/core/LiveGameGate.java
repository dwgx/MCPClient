package net.marcloud.mcp.core;

import static org.junit.Assert.fail;

import java.util.function.BooleanSupplier;

import org.junit.Assume;

/**
 * Skip-vs-fail gate shared by the game-dependent {@code *LiveIT} scaffolds
 * ({@code GuiClickLiveIT}, {@code SeamOnLiveConnectionLiveIT}, and the
 * {@code drivers.act} trio). It exists because those ITs used to gate TWICE —
 * {@code Assume(mcp.it.live)} and then {@code Assume(game reachable)} — so an
 * operator who explicitly asked for a live run with {@code -Dmcp.it.live=true}
 * still got "skipped, BUILD SUCCESS". A surefire report in this repo recorded
 * exactly that: the flag set, the methods skipped, the build green. Silent
 * success on an explicit live request is the worst possible answer, because it
 * is indistinguishable from having verified the behaviour.
 *
 * <p>The truth table this enforces:
 * <ul>
 *   <li>live flag absent → SKIP. Correct: nobody asked for a live run.</li>
 *   <li>live flag set, game absent → FAIL. The operator asked for something this
 *       JVM cannot deliver, and must be told rather than reassured.</li>
 *   <li>live flag set, game present → RUN.</li>
 * </ul>
 *
 * <p>Why the game can never be present under surefire/failsafe:
 * {@link GameAccess} reads {@code Minecraft.getMinecraft()}, a static singleton
 * that is only populated by the game's own bootstrap, so it is null in a forked
 * test JVM by construction. That makes FAIL the only branch these five ITs can
 * reach here — they are honest tombstones pointing at the real live route, not
 * working tests. Real live verification in this repo goes through the MCP socket
 * and {@code eval_java}; {@code scripts/nav-astar-probe.py} is the worked example.
 *
 * <p>Deliberately NOT merged with {@code NativeDebugOpLiveIT}'s own gate, whose
 * shape this copies. That one takes three inputs (live, required, available)
 * because a missing native DLL is a legitimate local condition, so it needs a
 * separate {@code -Dmcp.it.nativeRequired} flag to escalate SKIP to FAIL. Here
 * {@code -Dmcp.it.live} IS the request, so two inputs suffice; folding them into
 * one helper would force a meaningless third argument on five call sites.
 */
public final class LiveGameGate {

    private LiveGameGate() {
    }

    /** The operator's explicit request for a live run. */
    public static final boolean LIVE = Boolean.getBoolean("mcp.it.live");

    /** The real live-verification route, named in every FAIL message. */
    public static final String LIVE_ROUTE = "scripts/nav-astar-probe.py";

    /** Gate decision; RUN means the test body may execute. */
    public enum Gate { RUN, SKIP, FAIL }

    /**
     * Pure decision behind {@link #require}, so the truth table can be pinned
     * headlessly without a game. See the class javadoc for the reasoning.
     */
    public static Gate gate(boolean live, boolean gameUp) {
        if (!live) {
            return Gate.SKIP;
        }
        return gameUp ? Gate.RUN : Gate.FAIL;
    }

    /**
     * Outcome of touching the live game: whether the precondition held, plus WHY
     * it did not. The old ITs wrote {@code catch (Throwable noGame) { up = false; }}
     * and dropped the exception on the floor, so a real breakage (a renamed
     * vanilla field, a botched reflection seam) looked identical to "no game
     * running". Keeping the reason is the difference between a diagnosable
     * failure and another dead end.
     */
    public record Liveness(boolean up, String reason) {

        static Liveness notAsked() {
            return new Liveness(false, "not probed (-Dmcp.it.live is not set)");
        }
    }

    /**
     * Runs {@code probe} defensively. Off a live client the probe typically NPEs
     * on a null {@code Minecraft} singleton, so a throw is an expected answer
     * here, not an error — but the reason is retained rather than swallowed.
     */
    public static Liveness probe(BooleanSupplier probe) {
        try {
            return probe.getAsBoolean()
                    ? new Liveness(true, null)
                    : new Liveness(false, "probe returned false without throwing "
                            + "(Minecraft.getMinecraft() == null, or the player is not in a world)");
        } catch (Throwable noGame) {
            return new Liveness(false, noGame.getClass().getName() + ": " + noGame.getMessage());
        }
    }

    /** Message shown when no live run was requested. */
    public static String skipMessage(String what) {
        return what + ": not requested; re-run with -Dmcp.it.live=true "
                + "(and -Dcore.it.skip=false under failsafe) to demand it";
    }

    /** Message shown when a live run was requested but this JVM cannot host one. */
    public static String failMessage(String what, String reason) {
        return what + ": -Dmcp.it.live=true was requested but no live game is reachable. "
                + "GameAccess reads Minecraft.getMinecraft(), a static singleton populated only "
                + "by the game's own bootstrap, so it is null in a forked surefire/failsafe JVM "
                + "by construction — this test can never pass here. Probe reason: " + reason
                + ". Verify live through the MCP socket and eval_java instead; " + LIVE_ROUTE
                + " is the worked example. Failing rather than skipping because you asked for a "
                + "live run and a green skip would have looked like one had happened.";
    }

    /**
     * Enforces {@link #gate} for one IT method: returns normally to RUN, throws an
     * assumption violation to SKIP, and throws an assertion error to FAIL.
     *
     * @param what  short description of the precondition, e.g. "live GUI screen"
     * @param probe touches the live game; may throw, and its throw is reported
     */
    public static void require(String what, BooleanSupplier probe) {
        // Only touch the probe when a live run was actually requested: off the game
        // it class-loads Minecraft and throws, and paying that on every ordinary
        // `mvn test` buys nothing once the gate has already decided to skip.
        Liveness live = LIVE ? probe(probe) : Liveness.notAsked();
        switch (gate(LIVE, live.up())) {
            case RUN -> {
                // The game is here; the caller's body runs.
            }
            case SKIP -> Assume.assumeTrue(skipMessage(what), false);
            case FAIL -> fail(failMessage(what, live.reason()));
        }
    }
}
