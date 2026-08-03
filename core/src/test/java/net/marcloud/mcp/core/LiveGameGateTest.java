package net.marcloud.mcp.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.AssumptionViolatedException;
import org.junit.Test;

/**
 * Headless guard for {@link LiveGameGate}, the skip-vs-fail wiring shared by the
 * six game-dependent {@code *LiveIT} scaffolds. Same technique as
 * {@code NativeDebugGateTest}: exercise the pure decision so the truth table is
 * pinned without a game.
 *
 * <p>The assertion that matters is {@link #liveRequestedButGameAbsentFails()}.
 * Before this change every IT resolved that case with a second
 * {@code Assume.assumeTrue}, i.e. SKIP, which is exactly the recorded defect: an
 * operator ran with {@code -Dmcp.it.live=true} and got seven skips and BUILD
 * SUCCESS. This test goes red on that old semantics rather than merely failing
 * to compile, so it can distinguish "gate rewritten" from "gate reverted".
 */
public class LiveGameGateTest {

    @Test
    public void liveNotRequestedSkips() {
        assertEquals("nobody asked for a live run, so skipping is the honest answer",
                LiveGameGate.Gate.SKIP, LiveGameGate.gate(false, false));
    }

    @Test
    public void liveNotRequestedSkipsEvenIfTheGameIsSomehowUp() {
        // Belt-and-braces on the flag being the sole trigger: an unrequested live
        // run must not start just because a game happens to be reachable.
        assertEquals(LiveGameGate.Gate.SKIP, LiveGameGate.gate(false, true));
    }

    @Test
    public void liveRequestedButGameAbsentFails() {
        assertEquals("an explicit -Dmcp.it.live=true with no reachable game must FAIL; "
                        + "skipping here is the defect that made a live request report success",
                LiveGameGate.Gate.FAIL, LiveGameGate.gate(true, false));
    }

    @Test
    public void liveRequestedAndGamePresentRuns() {
        assertEquals(LiveGameGate.Gate.RUN, LiveGameGate.gate(true, true));
    }

    @Test
    public void failMessageNamesTheNullSingletonAndTheRealLiveRoute() {
        String msg = LiveGameGate.failMessage("live GUI screen", "NullPointerException: mc");
        assertTrue("must name Minecraft.getMinecraft() so the reader knows WHY it can never pass",
                msg.contains("Minecraft.getMinecraft()"));
        assertTrue("must point at the real live-verification route",
                msg.contains("scripts/nav-astar-probe.py"));
        assertTrue("must carry the swallowed probe reason through to the operator",
                msg.contains("NullPointerException: mc"));
    }

    @Test
    public void probeKeepsTheThrownReasonInsteadOfSwallowingIt() {
        LiveGameGate.Liveness l = LiveGameGate.probe(() -> {
            throw new IllegalStateException("no game here");
        });
        assertFalse("a throwing probe means the game is not up", l.up());
        assertTrue("the old catch(Throwable) dropped this; the reason must survive: " + l.reason(),
                l.reason().contains("IllegalStateException")
                        && l.reason().contains("no game here"));
    }

    @Test
    public void probeReportsAPlainFalseWithoutInventingAnException() {
        LiveGameGate.Liveness l = LiveGameGate.probe(() -> false);
        assertFalse(l.up());
        assertTrue("a quiet false still needs a reason: " + l.reason(),
                l.reason().contains("returned false"));
    }

    @Test
    public void probeReportsSuccessWithNoReason() {
        LiveGameGate.Liveness l = LiveGameGate.probe(() -> true);
        assertTrue(l.up());
        assertEquals(null, l.reason());
    }

    @Test
    public void skipMessageTellsTheOperatorHowToDemandALiveRun() {
        String msg = LiveGameGate.skipMessage("live GUI screen");
        assertTrue("a skip must name the flag that turns it into a real attempt",
                msg.contains("-Dmcp.it.live=true"));
        assertTrue("and the failsafe knob, since the ITs only run under failsafe",
                msg.contains("core.it.skip"));
    }

    /**
     * The IT count stated in prose must match the number of ITs actually using the gate.
     *
     * <p>Written because that number already drifted: the class javadoc said "these five ITs" after
     * {@code HoldLiveIT} was wired in as a sixth by a different change, and the count is exactly what
     * the next reader checks the wiring against -- a wrong one invites believing a class silently
     * dropped out of the gate. This repo has been bitten by a number-in-prose twice before (a commit
     * count pinned in a doc that changed on the next commit; a token count copied wrong across three
     * files), and the lesson both times was that a number no test owns will drift.
     *
     * <p>Derived from the filesystem rather than a hand-kept list, so adding a seventh IT turns this
     * red instead of quietly making four comments wrong.
     */
    @Test
    public void theStatedItCountMatchesTheItsActuallyUsingTheGate() throws Exception {
        java.nio.file.Path testRoot = java.nio.file.Path.of("src/test/java");
        assertTrue("this test must run from the core module directory", java.nio.file.Files
                .isDirectory(testRoot));

        java.util.List<String> users;
        try (var walk = java.nio.file.Files.walk(testRoot)) {
            users = walk.filter(f -> f.getFileName().toString().endsWith("LiveIT.java"))
                    .filter(f -> {
                        try {
                            return java.nio.file.Files.readString(f).contains("LiveGameGate");
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException("unreadable: " + f, e);
                        }
                    })
                    .map(f -> f.getFileName().toString())
                    .sorted()
                    .toList();
        }

        String number = switch (users.size()) {
            case 5 -> "five";
            case 6 -> "six";
            case 7 -> "seven";
            case 8 -> "eight";
            default -> String.valueOf(users.size());
        };
        String gateSource = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/test/java/net/marcloud/mcp/core/LiveGameGate.java"));
        assertTrue(users.size() + " ITs use LiveGameGate (" + users + ") but its javadoc does not "
                        + "say \"" + number + "\" -- update the prose, or the next reader will "
                        + "believe one silently dropped out of the gate",
                gateSource.contains(number + " ITs") || gateSource.contains(number + " call sites"));
    }

    /**
     * The decision table above was pinned; the step that ACTS on it was not, and that step is the
     * entire point of the class. Swapping the {@code fail} for an {@code Assume} restores the original
     * silent-success defect and, until these three, left every unit test green -- the fourth no-op
     * assertion found in this batch. A truth table nobody obeys is documentation, not a gate.
     */
    @Test
    public void enforcingRunLetsTheTestBodyProceed() {
        LiveGameGate.enforce(LiveGameGate.Gate.RUN, "live GUI screen", null);
    }

    @Test
    public void enforcingSkipRaisesAnAssumptionRatherThanAFailure() {
        try {
            LiveGameGate.enforce(LiveGameGate.Gate.SKIP, "live GUI screen", null);
            fail("SKIP must abort the test");
        } catch (AssumptionViolatedException expected) {
            assertTrue("a skip must still tell the operator how to demand a live run",
                    expected.getMessage().contains("-Dmcp.it.live=true"));
        } catch (AssertionError wrong) {
            fail("SKIP must raise an assumption, not an assertion -- an assertion here would turn "
                    + "every ordinary `mvn verify` red: " + wrong.getMessage());
        }
    }

    @Test
    public void enforcingFailRaisesAnAssertionRatherThanAnAssumption() {
        try {
            LiveGameGate.enforce(LiveGameGate.Gate.FAIL, "live GUI screen", "probe threw NPE");
            fail("FAIL must abort the test");
        } catch (AssumptionViolatedException wrong) {
            fail("FAIL must raise an ASSERTION. Raising an assumption is the original defect: the "
                    + "operator asked for a live run with -Dmcp.it.live=true and the suite answered "
                    + "BUILD SUCCESS, indistinguishable from having verified the behaviour");
        } catch (AssertionError expected) {
            assertTrue("the failure must carry the probe's reason so a renamed vanilla field is "
                    + "diagnosable rather than looking like an absent game",
                    expected.getMessage().contains("probe threw NPE"));
            assertTrue("and must point at the route that can actually verify live",
                    expected.getMessage().contains(LiveGameGate.LIVE_ROUTE));
        }
    }
}
