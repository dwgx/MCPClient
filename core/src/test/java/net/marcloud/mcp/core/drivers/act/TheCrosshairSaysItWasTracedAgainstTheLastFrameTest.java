package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.marcloud.mcp.core.util.Belief;
import net.marcloud.mcp.core.util.Graded;
import org.junit.Test;

/**
 * The crosshair seam used to say "never null" while handing back a ray traced against the frame
 * before last. Now it says so in its own type.
 *
 * <p><b>The lie, stated exactly.</b> {@code ActActuator.mouseOver()} returned
 * {@code Target.miss()} or a hit, and its declaration claimed "what the player is currently
 * looking at (crosshair ray), never null". The only production implementation satisfied that by
 * reading {@code mc.objectMouseOver}, a field written in exactly two places:
 * {@code Minecraft.runTick} (which our act loop enters <i>before</i> reaching) and
 * {@code EntityRenderer.renderWorld}. So an act-layer reader was handed a ray traced against the
 * PREVIOUS frame's rotation, and the seam had no shape in which to say so.
 *
 * <p><b>What a reader can now ask.</b> {@code mayActOn()} -- "was there anything there to look at?"
 * -- and the {@code why} -- "is the thing I aimed at computed from the rotation I just made?" -- as
 * a value rather than as an argument somebody has to reconstruct from {@code Minecraft.java} line
 * numbers. The first of those questions had a false answer before: {@code Target.miss()} was
 * returned both when the ray genuinely hit nothing and when there was no client to trace with.
 */
public class TheCrosshairSaysItWasTracedAgainstTheLastFrameTest {

    /**
     * The primary claim, and the control that keeps it from being trivially true.
     *
     * <p>A traced ray is INFERRED; a seam that never traced anything is UNKNOWN. If both were the
     * same grade the layer would be decoration, so both are asserted here and they are different.
     */
    @Test
    public void aTracedRayAndNoRayAtAllAreNotTheSameClaim() {
        FakeActuator act = new FakeActuator();
        act.mouseOver = ActActuator.Target.block(3, 64, -2, ActActuator.Face.UP,
                new double[] {3.5, 64.0, -1.5}, 4.1);

        Graded<ActActuator.Target> traced = act.mouseOver();
        assertEquals("the block under the crosshair", ActActuator.Target.Kind.BLOCK, traced.value().kind());
        assertEquals("a ray that was traced against the previous frame's rotation is a real "
                        + "reading with a stale aim behind it, which is not a direct observation",
                Belief.INFERRED, traced.belief());
        assertTrue("so a caller may act on it without looking again: it did look",
                traced.mayActOn());
        assertEquals("and it must say WHICH staleness, because 'inferred' alone does not tell a "
                + "reader whether the value is one frame old or one session old: " + traced.why(),
                ActActuator.STALE_ROTATION, traced.why());

        Graded<ActActuator.Target> nothing = ActActuator.noRay();
        assertEquals("with no client there was nothing traced at all, which is not an inference "
                        + "about the world but the absence of one",
                Belief.UNKNOWN, nothing.belief());
        assertFalse("so it must refuse to be acted on -- this is the half of the old lie that a "
                + "caller could not previously hear at all", nothing.mayActOn());
        assertEquals(ActActuator.NO_RAY, nothing.why());
    }

    /**
     * The strongest form of the same thing: the two answers are the SAME value with different
     * evidence behind them.
     *
     * <p>Before this change {@code Target.miss()} meant both of these, and a caller reading the
     * target alone could not tell "I looked at the crosshair and it hit nothing" from "there was
     * no crosshair to look at". Those two require opposite behaviour, which is the whole argument
     * for a third belief constant.
     */
    @Test
    public void theTwoMissesAreTheSameTargetAndDifferentClaims() {
        Graded<ActActuator.Target> tracedMiss = ActActuator.tracedLastFrame(ActActuator.Target.miss());
        Graded<ActActuator.Target> noRay = ActActuator.noRay();

        assertEquals("the wrapped value is identical, which is exactly why the value alone could "
                + "never carry this distinction", tracedMiss.value(), noRay.value());
        assertEquals(Belief.INFERRED, tracedMiss.belief());
        assertEquals(Belief.UNKNOWN, noRay.belief());
    }

    /**
     * The interface decision, asserted on the interface itself.
     *
     * <p>Adding a sibling {@code mouseOverGraded()} would have left {@code Target mouseOver()}
     * standing, and the next caller would have taken the ungraded door and been lied to in exactly
     * the way this file exists to end. So the shape change is not a preference: it is the property
     * that no method on the seam hands back an ungraded reading.
     */
    @Test
    public void theSeamOffersNoUngradedDoorAnyMore() throws Exception {
        List<String> ungraded = new ArrayList<>();
        for (Method m : ActActuator.class.getDeclaredMethods()) {
            if (m.getReturnType() == ActActuator.Target.class) {
                ungraded.add(m.getName() + "(): Target");
            }
        }
        assertEquals("a method on this seam that still returns a bare Target is the old lie with a "
                + "new neighbour: a caller has no reason to prefer the honest one. Found: " + ungraded,
                new ArrayList<String>(), ungraded);
        assertEquals("and the graded one is the only way in",
                net.marcloud.mcp.core.util.Graded.class, ActActuator.class
                        .getMethod("mouseOver").getReturnType());
    }

    /**
     * The live implementation, read from its own bytecode.
     *
     * <p>{@code LivePlayerActuator} cannot be constructed headlessly -- {@code GameAccess} reads
     * {@code Minecraft.getMinecraft()}, a static singleton that only the game's bootstrap fills --
     * so asserting on its behaviour here would mean asserting on nothing. What CAN be checked is
     * the shape of its decision: every answer it can give goes through one of the two interface
     * graders, so it cannot construct a grade of its own and cannot quietly reach OBSERVED.
     */
    @Test
    public void theLiveImplementationRoutesEveryAnswerThroughTheTwoGraders() throws IOException {
        org.objectweb.asm.tree.MethodNode mouseOver = method(
                "net/marcloud/mcp/core/drivers/act/LivePlayerActuator", "mouseOver");
        int traced = 0;
        int noRay = 0;
        int ownGrades = 0;
        for (org.objectweb.asm.tree.AbstractInsnNode n = mouseOver.instructions.getFirst();
             n != null; n = n.getNext()) {
            if (!(n instanceof org.objectweb.asm.tree.MethodInsnNode)) {
                continue;
            }
            org.objectweb.asm.tree.MethodInsnNode call = (org.objectweb.asm.tree.MethodInsnNode) n;
            if (call.owner.equals("net/marcloud/mcp/core/drivers/act/ActActuator")
                    && call.name.equals("tracedLastFrame")) {
                traced++;
            }
            if (call.owner.equals("net/marcloud/mcp/core/drivers/act/ActActuator")
                    && call.name.equals("noRay")) {
                noRay++;
            }
            if (call.owner.equals("net/marcloud/mcp/core/util/Graded")) {
                ownGrades++;
            }
        }
        assertEquals("every traced answer must come from the one shared derivation, or two "
                + "implementations end up with two private opinions about the same staleness",
                3, traced);
        assertEquals("and the absent-client answer from the other one", 1, noRay);
        assertEquals("LivePlayerActuator must build no grade of its own: a private grader here is "
                + "exactly the drift the two shared doors exist to prevent", 0, ownGrades);
    }

    private static org.objectweb.asm.tree.MethodNode method(String internalName, String name)
            throws IOException {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes(internalName)).accept(node, 0);
        for (org.objectweb.asm.tree.MethodNode m : node.methods) {
            if (m.name.equals(name)) {
                return m;
            }
        }
        throw new AssertionError("no method named " + name + " on " + internalName
                + ": this test has drifted off the code it guards");
    }

    private static byte[] bytes(String internalName) throws IOException {
        String resource = internalName + ".class";
        try (java.io.InputStream in = TheCrosshairSaysItWasTracedAgainstTheLastFrameTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("no class resource for " + resource + " on the test classpath");
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}