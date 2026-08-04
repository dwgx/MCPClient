package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * The navigation controller must close distance from any heading, and fail honestly otherwise.
 *
 * <p>These are geometry tests, which is the part that can be settled without a game: whether the
 * axes it chooses actually point at the target. Whether vanilla then moves the player the way its
 * own formula says it will is a live question, and the live check asserts distance CLOSED rather
 * than that the player moved -- because a mirrored axis convention produces motion either way.
 */
public class NavControllerTest {

    /**
     * Steps the controller, moving the fake along the axes it asked for.
     *
     * <p>The simulation applies vanilla's rotation ({@code Entity.moveFlying:1242-1243}) rather than
     * trusting the controller's own inversion, so a sign error in {@code steer} shows up as the
     * player walking away from the target instead of being cancelled out by a matching error here.
     */
    private static ActOutcome walk(NavController nav, FakeActuator act, int maxTicks, double speed) {
        ActOutcome out = null;
        for (int i = 0; i < maxTicks && (out == null || !out.terminal()); i++) {
            out = nav.tick(act);
            if (out.terminal()) {
                break;
            }
            double yaw = Math.toRadians(act.yaw);
            double cos = Math.cos(yaw);
            double sin = Math.sin(yaw);
            double mx = nav.strafe() * cos - nav.forward() * sin;
            double mz = nav.forward() * cos + nav.strafe() * sin;
            double len = Math.hypot(mx, mz);
            if (len > 1e-6) {
                act.nudge(speed * mx / len, 0, speed * mz / len);
            }
        }
        return out;
    }

    private static double distTo(FakeActuator act, double x, double z) {
        double[] p = act.position();
        return Math.hypot(x - p[0], z - p[2]);
    }

    @Test
    public void itArrivesWalkingStraightAhead() {
        FakeActuator act = new FakeActuator();
        act.setPosition(0, 64, 0);
        act.yaw = 0f;
        NavController nav = new NavController(0, 64, 10, 200);

        ActOutcome out = walk(nav, act, 200, 0.2);
        assertTrue("should arrive: " + out.message(), out.ok());
        assertTrue("and be within the arrival window, was " + distTo(act, 0, 10),
            distTo(act, 0, 10) <= 0.6);
    }

    @Test
    public void itArrivesFromEveryStartingHeading() {
        // The real assertion about steer(): the axes are computed relative to the CURRENT yaw, so a
        // player facing the wrong way must still close distance without the camera being turned.
        // A sign error in the inversion passes at yaw 0 and fails here.
        //
        // Ordered by heading rather than by literal, because the previous order hid a hole: it read
        // {0, 45, 90, 135, 180, -90, -135, 270} -- eight literals but seven headings, since -90 and
        // 270 are the same direction, and 315 was covered by neither. Written in heading order the
        // duplicate sits next to its twin where you cannot miss it. 270 is kept alongside -90
        // deliberately: callers pass yaw straight through from the client, which reports both forms,
        // and steer() converts to radians without normalising, so both literals are worth walking.
        //
        // What the non-cardinal headings actually buy, measured by mutating steer() and keeping this
        // test: negating strafe, and swapping the forward/strafe assignments, each turn ALL eight
        // headings red -- neither error has a heading where it cancels. Two subtler ones do. Flipping
        // only the sin sign, i.e. steering as if yaw were negated, leaves 0 and 180 GREEN because sin
        // is 0 there; swapping cos for sin leaves 45 and 225 green because cos equals sin there. Both
        // are invisible to itArrivesWalkingStraightAhead, which is why that test is not enough on its
        // own and why the sweep must include headings off the axes.
        //
        // 315 itself was green from birth under all four mutations, and that is expected rather than
        // disappointing: the error these mutations introduce is a linear form in (cos yaw, sin yaw),
        // which vanishes on at most one line through the origin, so any two headings off that line
        // already catch it and no single heading can ever be the sole witness. It is here to keep the
        // sweep from shrinking, not because it caught anything.
        float[] yaws = {0f, 45f, 90f, 135f, 180f, -135f, -90f, 270f, -45f};

        Set<Integer> octants = new TreeSet<>();
        List<String> failed = new ArrayList<>();
        for (float yaw : yaws) {
            octants.add(Math.floorMod((int) yaw, 360));

            FakeActuator act = new FakeActuator();
            act.setPosition(0, 64, 0);
            act.yaw = yaw;
            NavController nav = new NavController(8, 64, -6, 300);

            ActOutcome out = walk(nav, act, 300, 0.2);
            // Collected rather than asserted per-iteration so one run names EVERY heading that
            // failed, and a fail-fast loop reports only the first. WHICH headings break is the
            // diagnosis: all of them means the inversion is wrong at every yaw, while a green pair
            // names the term, since the surviving headings are exactly where that error cancels
            // (0/180 for a sin sign, 45/225 for cos read as sin).
            if (!out.ok()) {
                failed.add(yaw + " (" + out.message() + ")");
            }
            assertEquals("camera must not have been turned; nav owns walking, LOOK owns aim",
                null, act.lastSetYaw);
        }

        assertEquals("must arrive from every heading; these did not: " + failed,
            0, failed.size());
        // Guards the hole above from coming back: any edit that drops a heading, or picks two
        // literals that normalise to one direction, fails here instead of silently shrinking the
        // sweep to seven eighths of the circle while still looking like eight cases.
        for (int octant = 0; octant < 360; octant += 45) {
            assertTrue("sweep must cover heading " + octant + ", covered: " + octants,
                octants.contains(octant));
        }
    }

    @Test
    public void itDoesNotWalkBackwardsWhenTheTargetIsBehind() {
        // The mirrored-axis case, isolated. Target directly behind: if forward's sign were inverted
        // the player would accelerate away, and distance would grow rather than shrink.
        FakeActuator act = new FakeActuator();
        act.setPosition(0, 64, 0);
        act.yaw = 0f;                       // facing +Z
        NavController nav = new NavController(0, 64, -10, 200);   // target behind

        double before = distTo(act, 0, -10);
        walk(nav, act, 20, 0.2);
        double after = distTo(act, 0, -10);
        assertTrue("distance must shrink, not grow: " + before + " -> " + after, after < before);
    }

    @Test
    public void aWalledOffTargetFailsAsStuckRatherThanForever() {
        FakeActuator act = new FakeActuator();
        act.setPosition(0, 64, 0);
        act.yaw = 0f;
        act.collidedHorizontally = true;    // pressed against something
        NavController nav = new NavController(0, 64, 20, 500);

        // The fake never moves, so every tick is no-progress.
        ActOutcome out = null;
        for (int i = 0; i < 100 && (out == null || !out.terminal()); i++) {
            out = nav.tick(act);
        }
        assertTrue("must terminate", out != null && out.terminal());
        assertTrue("must fail rather than claim arrival", !out.ok());
        assertTrue("and must name the wall: " + out.message(),
            out.message().toLowerCase().contains("stuck"));
    }

    @Test
    public void standingStillWithoutContactIsNotCalledStuck() {
        // Both signals are required. A player can be motionless for reasons that are not a wall --
        // mid-air, or held by something -- and calling that a jam would send the caller after the
        // wrong problem. Without contact this must time out instead.
        FakeActuator act = new FakeActuator();
        act.setPosition(0, 64, 0);
        act.collidedHorizontally = false;
        NavController nav = new NavController(0, 64, 20, 30);

        ActOutcome out = null;
        for (int i = 0; i < 60 && (out == null || !out.terminal()); i++) {
            out = nav.tick(act);
        }
        assertTrue("must terminate", out != null && out.terminal());
        assertTrue("must not be reported as stuck: " + out.message(),
            !out.message().toLowerCase().contains("stuck"));
        assertTrue("should be the timeout: " + out.message(),
            out.message().toLowerCase().contains("gave up"));
    }

    @Test
    public void anUnreachableTargetTimesOutInsteadOfHoldingTheSlot() {
        FakeActuator act = new FakeActuator();
        act.setPosition(0, 64, 0);
        act.yaw = 0f;
        NavController nav = new NavController(0, 64, 5000, 25);

        ActOutcome out = walk(nav, act, 200, 0.2);
        assertTrue("must terminate rather than walk forever", out.terminal());
        assertTrue("must not claim success", !out.ok());
        assertTrue("must say it gave up and how far short: " + out.message(),
            out.message().contains("gave up") && out.message().contains("blocks out"));
        assertTrue("and must stop asking for movement once terminal",
            nav.forward() == 0f && nav.strafe() == 0f);
    }

    @Test
    public void cancelEndsItPromptly() {
        FakeActuator act = new FakeActuator();
        act.setPosition(0, 64, 0);
        NavController nav = new NavController(0, 64, 50, 500);
        nav.tick(act);
        nav.requestCancel();
        ActOutcome out = nav.tick(act);
        assertTrue("cancel must be terminal", out.terminal());
        assertTrue("and reported as a cancel: " + out.message(),
            out.message().toLowerCase().contains("cancel"));
    }
}
