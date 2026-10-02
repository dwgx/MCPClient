package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.junit.Test;

/**
 * Making the chosen tactic readable must not make the walk move differently.
 *
 * <p><b>What this pins.</b> Making {@code NavController}'s decision a value touched exactly one
 * thing that a reader would have to take on trust: the arithmetic. This file is the receipt. It
 * drives eight walks -- a clear one, a recovery, a recovery refused because the far side is a pit, a
 * point walk, a walk that publishes a jump for forty ticks, a body boxed in with nowhere to step, a
 * line with a deep drop on it and a line with water on it -- and hashes every published axis, every
 * jump, every tick count, every recovery cost and every terminal message.
 *
 * <p>The expected digest was taken from the working tree BEFORE {@code MoveTactic} existed, by
 * running this same eight-scenario trace against the unmodified controller. It is quoted here
 * verbatim so the claim can be checked rather than believed: before
 * {@code 7b7913703b103ec87e460e0ebf0394f30b5314a2da037d93b523d9d41b5b7d2f} (17427 characters, 340
 * lines) and after the same value, character for character.
 *
 * <p><b>Why a digest and not a list of assertions.</b> The property is "nothing moved", and the
 * strongest statement of nothing moved is that 17,427 characters are the same 17,427 characters.
 * Per-tick expectations would be a second copy of the controller's arithmetic, and a test that
 * duplicates the thing it is testing catches the duplication, not the drift.
 */
public class TheWalkThisSliceChangedMovesAsItDidBeforeTest {

    /** The trace of these eight walks, taken before the tactic became readable. */
    private static final String TRACE_SHA256_BEFORE =
            "7b7913703b103ec87e460e0ebf0394f30b5314a2da037d93b523d9d41b5b7d2f";
    private static final int TRACE_CHARS_BEFORE = 17427;
    private static final int TRACE_LINES_BEFORE = 340;

    private static final int GROUND = 63;
    private static final int FEET = 64;

    /** Open ground the body walks down, three cells wide either side of its lane. */
    private static FakeActuator flatCorridor() {
        FakeActuator act = new FakeActuator();
        for (int x = -2; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) {
                act.putBlock(x, GROUND, z);
            }
        }
        act.setPosition(0.5D, FEET, 0.5D);
        act.onGround = true;
        act.yaw = 0f;
        return act;
    }

    private static Standable standableOf(FakeActuator act) {
        net.marcloud.mcp.core.drivers.plan.BlockView view = BodySim.blockView(act);
        return (x, y, z) -> new net.marcloud.mcp.core.drivers.plan.Stance(x, y, z)
                .isStandable(view);
    }

    /**
     * Tick until terminal, moving the body the way the axes ask, recording every published axis.
     *
     * <p>The body is moved rather than teleported for the same reason the recovery tests move it: a
     * wedge is the axes held and the world refusing the step, and a scripted position cannot
     * produce one.
     */
    private static String drive(String label, NavController nav, FakeActuator act, int maxTicks) {
        StringBuilder sb = new StringBuilder();
        ActOutcome last = null;
        for (int i = 0; i < maxTicks; i++) {
            last = nav.tick(act);
            sb.append(String.format(Locale.ROOT, "%s t=%d f=%s s=%s j=%s%n",
                    label, i, nav.forward(), nav.strafe(), nav.jump()));
            if (last.terminal()) {
                break;
            }
            BodySim.step(act, nav.forward(), nav.strafe());
        }
        sb.append(String.format(Locale.ROOT, "%s END ticks=%d unwedgeTicks=%d unwedgeSteps=%d "
                        + "ok=%s terminal=%s cell=(%d,%d,%d) msg=%s%n",
                label, nav.ticks(), nav.unwedgeTicks(), nav.unwedgeSteps(),
                last != null && last.ok(), last != null && last.terminal(),
                (int) Math.floor(act.pos[0]), (int) Math.floor(act.pos[1]),
                (int) Math.floor(act.pos[2]), last == null ? "<none>" : last.message()));
        return sb.toString();
    }

    private static String allEightWalks() {
        StringBuilder all = new StringBuilder();

        all.append(drive("A-straight-stance",
                NavController.toStance(8.5D, FEET, 0.5D, 300, standableOf(flatCorridor())),
                flatCorridor(), 400));

        FakeActuator logWorld = flatCorridor();
        logWorld.putBlock(3, FEET, 0, "log");
        all.append(drive("B-wedged-recovery",
                NavController.toStance(8.5D, FEET, 0.5D, 300, standableOf(logWorld)),
                logWorld, 400));

        FakeActuator pitWorld = flatCorridor();
        pitWorld.putBlock(3, FEET, 0, "log");
        for (int x = 1; x <= 11; x++) {
            pitWorld.removeBlock(x, GROUND, 1);
        }
        all.append(drive("C-wedge-into-a-pit-is-refused",
                NavController.toStance(8.5D, FEET, 0.5D, 300, standableOf(pitWorld)),
                pitWorld, 400));

        all.append(drive("D-point-mode",
                new NavController(8.5D, FEET, 0.5D, 300),
                flatCorridor(), 400));

        // One block above the stance the body is standing in and directly overhead: the arrival
        // test fails on Y, so the walk stays alive and the jump axis is published on every tick.
        all.append(drive("E-step-up-jump",
                NavController.toStance(0.5D, FEET + 1, 0.5D, 40, standableOf(flatCorridor())),
                flatCorridor(), 60));

        FakeActuator boxedWorld = flatCorridor();
        boxedWorld.putBlock(3, FEET, 0, "log");
        for (int x = 1; x <= 11; x++) {
            boxedWorld.removeBlock(x, GROUND, 1);
            boxedWorld.removeBlock(x, GROUND, -1);
        }
        all.append(drive("F-boxed-in-no-side-standable",
                NavController.toStance(8.5D, FEET, 0.5D, 300, standableOf(boxedWorld)),
                boxedWorld, 400));

        FakeActuator dropWorld = flatCorridor();
        for (int x = 6; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) {
                dropWorld.removeBlock(x, GROUND, z);
            }
        }
        all.append(drive("H-deep-drop-on-the-line",
                NavController.toStance(11.5D, FEET, 0.5D, 300, standableOf(dropWorld)),
                dropWorld, 400));

        FakeActuator waterWorld = flatCorridor();
        for (int x = 6; x <= 12; x++) {
            waterWorld.putBlock(x, FEET, 0, "water");
        }
        all.append(drive("I-water-on-the-line",
                NavController.toStance(11.5D, FEET, 0.5D, 300, standableOf(waterWorld)),
                waterWorld, 400));

        return all.toString();
    }

    @Test
    public void everyOneOfEightWalksPublishesExactlyTheAxesItPublishedBefore() throws Exception {
        String trace = allEightWalks();

        assertEquals("the trace is the same LENGTH as before the tactic became readable, which is "
                        + "the cheap half of 'nothing moved'",
                TRACE_CHARS_BEFORE, trace.length());
        assertEquals("and the same number of lines, so no walk gained or lost a tick",
                TRACE_LINES_BEFORE, trace.split("\n").length);

        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(trace.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        assertEquals("every published axis, jump, tick count, recovery cost and terminal message "
                        + "of eight walks is character-for-character what it was before this slice",
                TRACE_SHA256_BEFORE, hex.toString());
    }
}