package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A raw movement axis must become a key press, not stay a fraction.
 *
 * <p>{@code MoveIntent} clamped its axes to [-1, 1] and published them as arbitrary floats, so
 * {@code forward: 0.5} made the player walk at half speed. Two things are wrong with that and only
 * one of them is cosmetic:
 *
 * <ul>
 *   <li><b>It is a tell.</b> Nobody walks at half speed; that is a machine with an analogue
 *       output, not a person on a keyboard.</li>
 *   <li><b>It costs a real property.</b> This is the part that matters under ADR-0005. Publishing
 *       +1 is what lets <i>vanilla's own</i> friction and acceleration ramp the player up; the
 *       moveFlying path normalises the diagonal, and the ground physics produce the ease-in that
 *       every client has. Publishing +0.3 bypasses that physics entirely, so the player
 *       accelerates instantly to a speed no key produces. Snapping restores real behaviour and
 *       costs nothing -- which makes it ADR-0005's "免费真实" category, not a trade.</li>
 * </ul>
 *
 * <p>{@code NavController} already snapped, and for exactly this reason. The two paths simply
 * disagreed about what a movement axis is, which is the same class of bug this repository has
 * deleted six copies of.
 */
public final class ARawMovementAxisIsAKeyPressNotAFractionTest {

    private static float[] axesOf(float forward, float strafe) {
        MoveIntent m = new MoveIntent(forward, strafe, false, false, false, 0);
        return new float[] {m.forward(), m.strafe()};
    }

    @Test
    public void noAxisEverComesOutAsAFraction() {
        // Every input in this table is a legal call. None of them may produce a fraction.
        float[][] inputs = {
            {0.0f, 0.0f}, {0.1f, 0.0f}, {0.3f, 0.0f}, {0.5f, 0.0f}, {0.99f, 0.0f},
            {1.0f, 0.0f}, {-0.2f, 0.0f}, {-1.0f, 0.0f},
            {0.0f, 0.4f}, {0.0f, -0.4f}, {0.5f, 0.5f}, {0.5f, -0.5f},
            {-0.3f, 0.7f}, {1.0f, 0.2f}, {2.0f, 0.0f}, {-2.0f, 3.0f},
        };
        for (float[] in : inputs) {
            float[] out = axesOf(in[0], in[1]);
            for (float v : out) {
                assertTrue("forward=" + in[0] + " strafe=" + in[1] + " produced " + v
                                + "; a person cannot press a fraction of a key, and publishing one "
                                + "also bypasses vanilla's own acceleration",
                        v == 0.0f || v == 1.0f || v == -1.0f);
            }
        }
    }

    @Test
    public void theAxesStillPointTheWayTheCallerAsked() {
        // Snapping must not be "round toward zero and lose the direction". The eight reachable
        // headings are the property, and losing one is how a walk ends up going the wrong way.
        assertEquals("full forward stays full forward", 1.0f, axesOf(1.0f, 0.0f)[0], 1e-6);
        assertEquals("back stays back", -1.0f, axesOf(-1.0f, 0.0f)[0], 1e-6);
        assertEquals("a strafe keeps its side", 1.0f, axesOf(0.0f, 0.9f)[1], 1e-6);
        assertEquals("and the other one too", -1.0f, axesOf(0.0f, -0.9f)[1], 1e-6);
        assertEquals("a mostly-forward bearing becomes a diagonal, which is what two keys give",
                1.0f, axesOf(0.9f, 0.9f)[0], 1e-6);
        assertEquals("with the strafe on the side asked for", 1.0f, axesOf(0.9f, 0.9f)[1], 1e-6);
    }

    @Test
    public void nearNeutralIsNoMovementRatherThanAStutter() {
        // A magnitude below the threshold is a release, not a 1-in-1000 keypress. This is the
        // "chatter" property NavController's tie-breaking exists for, and the raw path needs it
        // too: a caller nudging the axis must not produce a machine-gun of W taps.
        // Below HALF a key. The old threshold was 1e-6, a numeric guard rather than a deadband:
        // 0.001 became a full-speed walk while 0.0 was a standstill, and a caller nudging the
        // axis by a thousandth got a sprint.
        assertEquals("a tiny nudge is nothing", 0.0f, axesOf(0.001f, 0.0f)[0], 1e-6);
        assertEquals("on either axis", 0.0f, axesOf(0.0f, 0.001f)[1], 1e-6);
        assertEquals("and diagonal", 0.0f, axesOf(0.001f, 0.001f)[0], 1e-6);
        assertEquals("and anything under half a key, because vanilla has no partial forward",
                0.0f, axesOf(0.49f, 0.0f)[0], 1e-6);
        assertEquals("while half a key is a press", 1.0f, axesOf(0.5f, 0.0f)[0], 1e-6);
    }

    @Test
    public void theRawPathAndTheNavigatorNowAgreeOnWhatAnAxisIs() {
        // The actual bug was a disagreement between two paths, so the property is that they agree.
        for (float f : new float[] {-1f, -0.5f, 0f, 0.5f, 1f}) {
            for (float st : new float[] {-1f, -0.5f, 0f, 0.5f, 1f}) {
                double[] nav = NavController.nearestKeys(f, st);
                float[] raw = axesOf(f, st);
                assertEquals("the navigator and the raw path disagree at (" + f + "," + st + ")",
                        (float) nav[0], raw[0], 1e-6);
                assertEquals("(forward)", (float) nav[1], raw[1], 1e-6);
            }
        }
    }
}
