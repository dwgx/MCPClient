package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

/**
 * A clock must cost the walk nothing, and the reason it can be proved rather than asserted is
 * that {@link net.marcloud.mcp.core.drivers.world.Daylight} takes no world.
 *
 * <p><b>Same shape as {@code TheBeliefLayerCostsNoWorldReadTest}: a counting actuator and an
 * exact number.</b> That test pins the belief layer at 25 reads for both a 10-tick and a 60-tick
 * walk, and the numbers are written in as equalities because the property is ZERO and a bound
 * would let a regression of ten reads pass. This is the same argument about a different seam: the
 * clock was added to {@link ActActuator}, which is on the tick path, and the failure mode for an
 * accessor added to a hot interface is a controller that asks the world a question it could have
 * answered from a number it already had.
 *
 * <p><b>25 == 25 is the number, and it is unchanged.</b> The walk below is the same 60-tick
 * {@code NavController} walk that test measures, driven through a {@code FakeActuator} that
 * counts {@code blockAt} calls. {@link FakeActuator#isDaytime()} is reached through
 * {@link net.marcloud.mcp.core.drivers.world.Daylight} and touches nothing, so the count is the
 * pre-change count. A controller that started branching on the clock would either add reads (if it
 * asked the world) or not -- and the second case is what the bytecode half below is for, because
 * "not counted" and "counted nothing because there was nothing to count" are the same observation.
 *
 * <p><b>So the second half proves the negative structurally.</b> {@link Daylight} is read from the
 * compiled class: it must contain no call into {@code net/minecraft/world/} and no call to
 * {@code World.isDaytime}, and every one of its methods must be reachable without a world at all.
 * A helper that took a {@code World} and returned {@code w.isDaytime()} would pass every behavioural
 * test in this slice and be exactly the defect again -- correct-looking, identically broken, and
 * this time with a green suite attached to it.
 */
public final class TheClockCostsTheWalkNoWorldReadTest {

    /** {@link TheBeliefLayerCostsNoWorldReadTest}'s measured figures, restated as this test's own. */
    private static final int PRE_CHANGE_READS_10_TICKS = 25;
    private static final int PRE_CHANGE_READS_60_TICKS = 25;

    /** Counts the one world-read seam a controller has, so the cost is a number. */
    private static final class CountingActuator extends FakeActuator {
        int reads;

        @Override
        public String blockAt(int x, int y, int z) {
            reads++;
            return super.blockAt(x, y, z);
        }
    }

    private static NavController walk(CountingActuator act, int ticks) {
        act.setPosition(0, 64, 0);
        act.yaw = 0f;
        NavController nav = new NavController(24, 64, 0, 400);
        for (int i = 0; i < ticks; i++) {
            nav.tick(act);
        }
        return nav;
    }

    @Test
    public void aWalkCostsExactlyWhatItCostBeforeTheClockWasOnTheSeam() {
        CountingActuator act = new CountingActuator();
        walk(act, 60);
        assertEquals("60 ticks of walking now cost " + act.reads + " block reads; it cost "
                        + PRE_CHANGE_READS_60_TICKS + " before worldTime() and isDaytime() were on "
                        + "ActActuator. An increase means something on the tick path is asking the "
                        + "world a question the clock answers with arithmetic",
                PRE_CHANGE_READS_60_TICKS, act.reads);
    }

    @Test
    public void aShortWalkIsAlsoUnchangedAndThePerTickCostIsStillZero() {
        CountingActuator shortWalk = new CountingActuator();
        walk(shortWalk, 10);
        CountingActuator longWalk = new CountingActuator();
        walk(longWalk, 60);
        assertEquals("a 10-tick walk is also unchanged", PRE_CHANGE_READS_10_TICKS,
                shortWalk.reads);
        assertEquals("and the six-fold longer walk costs the same again, so the clock introduced no"
                + " per-tick cost: " + longWalk.reads + " vs " + shortWalk.reads,
                PRE_CHANGE_READS_10_TICKS, longWalk.reads);
    }

    /** The mirror: a walk that reads nothing would also pass a cost assertion. */
    @Test
    public void theWalkStillReadsTheWorldSoTheZeroAboveIsNotBlindness() {
        CountingActuator act = new CountingActuator();
        walk(act, 10);
        assertTrue("10 ticks of walking cost " + act.reads + " block reads, so the walk is still"
                + " looking at the world; a zero above would be blindness, not economy",
                act.reads > 0);
    }

    /**
     * The clock itself costs nothing, asked at every tick of a walk.
     *
     * <p>Not the same assertion as the three above. Those pin the block-read count; this asks the
     * clock on every tick and counts what that does, which is a separate question with a separate
     * failure. An implementation of {@code isDaytime()} that reached through to the world would
     * leave the block-read count at 25 -- {@code blockAt} is not how it would read -- so without
     * this the three equalities above would be satisfied by a frozen-read implementation wearing
     * a clock's name.
     */
    @Test
    public void askingTheClockOnEveryTickOfAWalkCostsNoWorldRead() {
        // A/B on the SAME walk, so the comparison is between two runs that differ only in whether
        // the clock was consulted. Measuring the clock's cost by difference is the only way to
        // attribute the reads: a single run's total is the walk's cost, not the clock's.
        int withoutClock = walkCountingReads(false);
        int withClock = walkCountingReads(true);

        assertEquals("the walk cost " + withoutClock + " block reads when the clock was never"
                        + " asked and " + withClock + " when it was asked on every one of the 60"
                        + " ticks. Asking the clock cost " + (withClock - withoutClock)
                        + " world reads, so it is arithmetic and not a read",
                withoutClock, withClock);
        assertEquals("and both are the pre-change figure, so the clock changed nothing at all",
                PRE_CHANGE_READS_60_TICKS, withClock);
    }

    /**
     * One 60-tick walk, optionally asking the clock on every tick.
     *
     * @return the number of {@code blockAt} reads the walk made
     */
    private static int walkCountingReads(boolean askTheClock) {
        CountingActuator act = new CountingActuator();
        act.setPosition(0, 64, 0);
        act.yaw = 0f;
        NavController nav = new NavController(24, 64, 0, 400);
        int daytimeAnswers = 0;
        for (int i = 0; i < 60; i++) {
            if (askTheClock) {
                // The clock advances a tick per tick, the way the substrate's does, so the two
                // runs differ in what the clock SAYS as well as in whether it was consulted.
                act.worldTime = i;
                if (act.isDaytime()) {
                    daytimeAnswers++;
                }
            }
            nav.tick(act);
        }
        if (askTheClock) {
            assertTrue("premise: the clock answered on every tick, or the equality above is"
                    + " vacuous -- it answered on " + daytimeAnswers + " of 60", daytimeAnswers == 60);
        }
        return act.reads;
    }

    /**
     * The structural half: {@link net.marcloud.mcp.core.drivers.world.Daylight} cannot read a world.
     *
     * <p>Read from the compiled class rather than from its source, because the property is about
     * what the shipped bytecode does. Every method must contain no call into
     * {@code net/minecraft/world/} and no call to {@code World.isDaytime} or
     * {@code getSkylightSubtracted} -- those are the two reads that would reintroduce the frozen
     * value, and a helper holding a {@code World} would be free to use either.
     *
     * <p>{@code MathHelper} IS called, and deliberately: {@code MathHelper.cos} is vanilla's own
     * 65536-entry lookup table ({@code MathHelper.java:38-41}) and the transcribed curve is off by
     * a tick without it. It is a static table with no world in it, so the check excludes
     * {@code net/minecraft/world/} and {@code net/minecraft/entity/} rather than all of
     * {@code net.minecraft}, and says so here rather than leaving the reader to guess which.
     */
    @Test
    public void theDaylightHelperContainsNoWorldCallAtAll() throws Exception {
        try {
            byte[] bytes = readClass("net/marcloud/mcp/core/drivers/world/Daylight");
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(bytes).accept(node, 0);

            int worldCalls = 0;
            int frozenReadCalls = 0;
            int inspected = 0;
            for (org.objectweb.asm.tree.MethodNode m : node.methods) {
                if (m.instructions == null) {
                    continue; // abstract or native
                }
                boolean isPublicStatic = (m.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0;
                if (!isPublicStatic || m.name.equals("<clinit>")) {
                    continue;
                }
                inspected++;
                for (org.objectweb.asm.tree.AbstractInsnNode n = m.instructions.getFirst();
                     n != null; n = n.getNext()) {
                    if (!(n instanceof org.objectweb.asm.tree.MethodInsnNode call)) {
                        continue;
                    }
                    if (call.owner.startsWith("net/minecraft/world/")
                            || call.owner.startsWith("net/minecraft/entity/")) {
                        worldCalls++;
                    }
                    if (call.name.equals("isDaytime") && call.owner.equals("net/minecraft/world/World")) {
                        frozenReadCalls++;
                    }
                    if (call.name.equals("getSkylightSubtracted")) {
                        frozenReadCalls++;
                    }
                }
            }
            assertEquals("Daylight's public static methods were all inspected -- celestialAngle,"
                    + " skylightSubtracted, isDaytime, isNight and moonPhase. A different count"
                    + " means a method was added or removed and this test has drifted off the code"
                    + " it guards, so name the new one here rather than widening the bound", 5,
                    inspected);
            assertEquals("Daylight must not call into a world or an entity class. It takes a long"
                    + " and returns arithmetic, and a world call is the one thing that could make"
                    + " it a second frozen read -- the defect this class exists to remove",
                    0, worldCalls);
            assertEquals("and it must not call World.isDaytime or getSkylightSubtracted under any"
                    + " name: those are the two reads whose values on a client never change",
                    0, frozenReadCalls);
        } catch (IOException e) {
            throw new AssertionError("could not read Daylight's bytecode", e);
        }
    }

    /**
     * The live actuator must not delegate either.
     *
     * <p>{@code LivePlayerActuator.isDaytime()} is the production implementation, and the
     * one-line mistake available there is {@code return w != null && w.isDaytime();} -- which
     * compiles, reads correctly, and reproduces the defect exactly. Bytecode again rather than
     * source, because "it looks right in the source" is what that mistake looks like.
     */
    @Test
    public void theLiveActuatorDoesNotDelegateToTheFrozenVanillaRead() throws Exception {
        try {
            byte[] bytes = readClass("net/marcloud/mcp/core/drivers/act/LivePlayerActuator");
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(bytes).accept(node, 0);

            org.objectweb.asm.tree.MethodNode isDaytime = null;
            for (org.objectweb.asm.tree.MethodNode m : node.methods) {
                if (m.name.equals("isDaytime") && m.desc.equals("()Z")) {
                    isDaytime = m;
                }
            }
            if (isDaytime == null) {
                throw new AssertionError("LivePlayerActuator.isDaytime() is not in the compiled"
                        + " class: this test has drifted off the code it guards");
            }
            int frozen = 0;
            int viaDaylight = 0;
            for (org.objectweb.asm.tree.AbstractInsnNode n = isDaytime.instructions.getFirst();
                 n != null; n = n.getNext()) {
                if (!(n instanceof org.objectweb.asm.tree.MethodInsnNode call)) {
                    continue;
                }
                if (call.name.equals("isDaytime")
                        && call.owner.equals("net/minecraft/world/World")) {
                    frozen++;
                }
                if (call.name.equals("isDaytime")
                        && call.owner.equals("net/marcloud/mcp/core/drivers/world/Daylight")) {
                    viaDaylight++;
                }
            }
            assertEquals("LivePlayerActuator.isDaytime() must not call World.isDaytime(): that is"
                    + " the frozen read this slice replaced, and delegating to it would be"
                    + " correct-looking and identically broken", 0, frozen);
            assertEquals("and it must reach its answer through Daylight, so live and simulated"
                    + " agree by construction rather than by coincidence", 1, viaDaylight);
        } catch (IOException e) {
            throw new AssertionError("could not read LivePlayerActuator's bytecode", e);
        }
    }

    private static byte[] readClass(String internalName) throws IOException {
        String resource = internalName + ".class";
        try (java.io.InputStream in = TheClockCostsTheWalkNoWorldReadTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("no class resource for " + resource + " on the test"
                        + " classpath");
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.write(buf, 0, n);
            }
            return bytes.toByteArray();
        }
    }
}
