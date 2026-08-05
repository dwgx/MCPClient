package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.io.transport.ToolRegistry;

/**
 * The numbers {@code find_block}'s shell walk is built from, pinned at the values that make them
 * true rather than at the symbols that name them.
 *
 * <p><b>Why this file exists.</b> Five deliberate one-token edits to {@link BlockFinder} were
 * compiled and run against the whole suite and every one stayed GREEN: {@code MAX_LIMIT} cut from
 * 64 to 4, the early-exit boundary compared against the NEAREST kept hit instead of the WORST one,
 * {@code dist} rounded to whole blocks instead of two decimals, the sampled offset transposed in
 * x/z against the offset that is reported, and the {@code trim()} dropped from the type parser.
 * Each one changes the coordinate or the distance the model then acts on, and none of them was
 * observable: the clamp assertion read the very constant it was testing, the early-exit test drove
 * the boundary at limit 1 where the two comparisons coincide, the distance assertions all used
 * values that are already integral, no test passed whitespace in {@code types}, and nothing drove
 * {@link BlockFinder#find} at all.
 *
 * <p><b>Why one assertion is on the compiled bytecode.</b> {@code find} pairs the offset it SAMPLES
 * with the offset {@code collectShell} EMITS, and only the emit side has a seam: every test reaches
 * the walk through {@code search(Sampler, ...)}, which is the point of that seam, so the pairing
 * itself is unreachable without a live {@code WorldClient}. Reading the argument order the call site
 * actually compiles to is the same technique the {@code LocalGrid} column boundaries use for their
 * private, world-bound probes, and it disagrees with the mutation instead of agreeing with it.
 *
 * <p>Distances here are the exact two-decimal values the walk produces for those offsets:
 * {@code sqrt(12) = 3.46} and {@code sqrt(20) = 4.47}. Written as literals on purpose -- an expected
 * value recomputed from the code under test agrees with whatever the code became.
 */
public class BlockFinderShellWalkIsPinnedAtItsBoundaryTest {

    /** Internal name of the class under test, whose own lambda call site is read below. */
    private static final String BLOCK_FINDER = "net/marcloud/mcp/core/drivers/world/BlockFinder";

    /** A world as a map from offset to block name, matching the fake the sibling test drives. */
    private static BlockFinder.Sampler world(Map<String, String> blocks) {
        return (dx, dy, dz) -> blocks.get(dx + "," + dy + "," + dz);
    }

    /**
     * A fixture with more matches than the ceiling, so "exactly 64" is a clamp rather than an
     * inventory. Spread over six axes because a single line tops out at {@code MAX_RADIUS} hits,
     * which would let the radius clamp fire first and leave the limit ceiling untouched.
     */
    private static Map<String, String> sixtyFivePlusMatches() {
        Map<String, String> many = new HashMap<>();
        for (int i = 1; i <= 12; i++) {
            many.put(i + ",0,0", "stone");
            many.put(-i + ",0,0", "stone");
            many.put("0," + i + ",0", "stone");
            many.put("0," + -i + ",0", "stone");
            many.put("0,0," + i, "stone");
            many.put("0,0," + -i, "stone");
        }
        return many;
    }

    @Test
    public void theHitCeilingIsSixtyFourAndIsDrivenAtSixtyFourAndOnePastIt() {
        Map<String, String> many = sixtyFivePlusMatches();
        assertEquals("the fixture has to hold MORE matches than the ceiling, or \"exactly 64 hits\" "
                + "is satisfied by simply running out of blocks and pins nothing",
                72, many.size());

        // The literal, because the existing clamp assertion reads MAX_LIMIT on BOTH sides and so
        // agrees with any value the constant takes -- including 4.
        assertEquals("the ceiling must be 64, which is the range find_block advertises to the model "
                + "as \"max hits, 1-64\". A lower real ceiling makes that range a lie: the model asks "
                + "for 30 ore veins, receives 4, and reads the short list as \"there are only 4 "
                + "nearby\" -- a false negative it then plans around by digging or travelling.",
                64, BlockFinder.MAX_LIMIT);
        assertTrue("and the advertised range must quote the ceiling production enforces, not a "
                + "number hand-copied beside it",
                limitSchemaDescription().contains("1-" + BlockFinder.MAX_LIMIT));

        assertEquals("asking for exactly 64 must return exactly 64: this is the top of the "
                + "advertised range, so it is the one value a caller can assume is honoured in full",
                64, BlockFinder.search(world(many), 0, 0, 0, "stone", 16, 64).size());
        assertEquals("and one past the ceiling must still return 64 -- clamped, never fewer. "
                + "Silently answering 4 here tells the model the neighbourhood is nearly empty when "
                + "72 blocks matched.",
                64, BlockFinder.search(world(many), 0, 0, 0, "stone", 16, 65).size());
        assertEquals("the advertised DEFAULT must survive the clamp too: find_block sends limit 8 "
                + "when the caller names none, so a ceiling below 8 truncates every default query",
                8, BlockFinder.search(world(many), 0, 0, 0, "stone", 16, 8).size());
        assertTrue("the default the schema promises has to be reachable under the ceiling at all",
                limitSchemaDescription().contains("default 8") && BlockFinder.MAX_LIMIT >= 8);
    }

    /**
     * The early exit, driven where the two candidate boundaries differ.
     *
     * <p>The existing guard for this exit uses limit 1, and at limit 1 the WORST kept hit IS the
     * nearest one -- the two comparisons are the same expression there, so no fixture at limit 1 can
     * tell them apart. This drives limit 2, where they diverge: hit #2 must still be allowed to
     * improve after the shell that filled the list.
     */
    @Test
    public void theWalkOutlivesTheShellThatFilledTheLimitWhileANearerHitIsStillPossible() {
        // Shell distance is a LOWER bound on euclidean distance, which is the whole reason the exit
        // needs a comparison at all: the corner of shell 2 is 3.46 away while the face of shell 3 is
        // only 3.0, so shell 3 still holds a nearer block than one already kept from shell 2.
        Map<String, String> w = Map.of(
                "1,0,0", "stone",     // shell 1, dist 1.0  -- fills slot #1
                "2,2,2", "stone",     // shell 2, dist 3.46 -- fills slot #2, and is the WORST kept
                "3,0,0", "stone");    // shell 3, dist 3.0  -- genuinely nearer than the corner above
        List<BlockFinder.Hit> hits = BlockFinder.search(world(w), 100, 64, 200, "stone", 16, 2);

        assertEquals("two hits when two were asked for", 2, hits.size());
        assertEquals("the nearest is unaffected either way -- it is hit #2 that the boundary decides",
                1.0, hits.get(0).dist(), 0.001);
        assertEquals(101, hits.get(0).x());

        // The catch: both boundaries return TWO hits here, so only the identity of hit #2 separates
        // them. Comparing the shell against the nearest kept hit stops at shell 2 and keeps the
        // corner; comparing against the worst reaches shell 3 and replaces it.
        assertEquals("hit #2 must be the shell-3 FACE at x=103, not the shell-2 CORNER at x=102. "
                + "Stopping as soon as the shell passes the NEAREST kept hit hands the model a block "
                + "0.46 further away while a nearer one sat unvisited one shell out -- so the list is "
                + "wrong rather than merely short, and the model walks to the further block believing "
                + "nothing closer exists.",
                103, hits.get(1).x());
        assertEquals("the wrong winner is also at the wrong height, which is what sends a dig or a "
                + "navigate to a block that is not there", 64, hits.get(1).y());
        assertEquals(200, hits.get(1).z());
        assertEquals("and its distance is the shell-3 face's 3.0, not the corner's 3.46",
                3.0, hits.get(1).dist(), 0.001);
    }

    /**
     * {@code dist} keeps two decimals, driven on offsets whose true distance is NOT integral.
     *
     * <p>Every distance the sibling test asserts is already a whole number (2.0, 8.0, 1..30), so a
     * rounding that drops the decimals is invisible there. These three offsets are the ones that can
     * see it, and the integral one is kept as the control: it must not move.
     */
    @Test
    public void theReportedDistanceKeepsTwoDecimalsRatherThanWholeBlocks() {
        List<BlockFinder.Hit> corner = BlockFinder.search(
                world(Map.of("2,2,2", "stone")), 0, 0, 0, "stone", 16, 1);
        assertEquals("one hit found, or the distance below is vacuous", 1, corner.size());
        assertEquals("a block at (2,2,2) is sqrt(12) = 3.46 away and must be reported as 3.46. "
                + "Rounded to 3.0 it becomes indistinguishable from the block at (3,0,0), which is "
                + "genuinely nearer -- and since the rounded value IS the sort key, the model is then "
                + "handed the further block as \"the nearest\".",
                3.46, corner.get(0).dist(), 0.001);

        List<BlockFinder.Hit> nearReach = BlockFinder.search(
                world(Map.of("4,2,0", "stone")), 0, 0, 0, "stone", 16, 1);
        assertEquals(1, nearReach.size());
        assertEquals("a block at (4,2,0) is sqrt(20) = 4.47 away. The tool tells the model dist is "
                + "measured feet-to-index and that a hit reported just inside 4.5 can still be out of "
                + "reach, so 4.47 is the reading that makes it get closer first. Reported as 4.0 the "
                + "model believes it has half a block of margin, acts from where it stands, and the "
                + "interaction silently does nothing.",
                4.47, nearReach.get(0).dist(), 0.001);

        List<BlockFinder.Hit> face = BlockFinder.search(
                world(Map.of("3,0,0", "stone")), 0, 0, 0, "stone", 16, 1);
        assertEquals(1, face.size());
        assertEquals("an axis-aligned block stays exactly 3.0: the two decimals must not perturb a "
                + "distance that is already whole, or every reading gains noise",
                3.0, face.get(0).dist(), 0.001);
    }

    /**
     * A type list must survive the whitespace a human-written tool call carries.
     *
     * <p>The error message find_block emits spells the argument as {@code "oak_log,birch_log"}, but a
     * model writing prose-adjacent JSON puts a space after the comma as often as not, and only the
     * FIRST name in the list is unaffected by losing it. Every existing parse assertion uses tight
     * strings, so nothing sees the difference.
     */
    @Test
    public void aSpaceAfterTheCommaStillNamesABlockType() {
        assertTrue("\"oak_log, birch_log\" must match birch_log. Untrimmed, the wanted set holds "
                + "\" birch_log\" with a leading space, no registry name ever equals that, and the "
                + "model is told the block is not nearby -- so it stops looking for a tree that is "
                + "two blocks away.",
                BlockFinder.matches("birch_log", "oak_log, birch_log"));
        assertTrue("the first name in the list is immune to the missing trim, which is exactly why "
                + "this stayed green: it must keep matching too",
                BlockFinder.matches("oak_log", "oak_log, birch_log"));
        assertTrue("a qualified name after the comma is immune as well, because stripping the "
                + "namespace removes the leading space with it -- so the unqualified form above is "
                + "the case that has to be driven",
                BlockFinder.matches("birch_log", "oak_log, minecraft:birch_log"));

        // Through the real search too: matches() has no production caller, so an assertion on it
        // alone constrains the parser only by way of a shared private helper.
        List<BlockFinder.Hit> hits = BlockFinder.search(world(Map.of("2,0,0", "minecraft:birch_log")),
                0, 0, 0, "oak_log, birch_log", 8, 4);
        assertEquals("the search itself must find the type named after the space, not answer \"no "
                + "match ... within 8 blocks\" for a block it walked straight past",
                1, hits.size());
        assertEquals("birch_log", hits.get(0).block());
        assertEquals(2, hits.get(0).x());
    }

    /**
     * The offset that is SAMPLED must be the offset that is REPORTED.
     *
     * <p>Two halves of one pairing. The emit half has a seam and is driven below. The sample half is
     * the lambda inside {@code find}, which needs a live {@code WorldClient}, so its argument order
     * is read off the compiled call site -- the only place that pairing is observable from
     * {@code core/src/test}. A transposition there is not a smaller answer, it is a confidently
     * wrong coordinate: the block is found, and reported mirrored across the x=z diagonal.
     */
    @Test
    public void theSampledOffsetIsTheOffsetThatGetsReported() {
        MethodNode lambda = findSamplerLambda();
        MethodInsnNode add = callTo(lambda, "add");
        assertNotNull("find must offset the origin by the walk's own (dx, dy, dz) before sampling",
                add);
        assertEquals("and it must be BlockPos.add that does it",
                "net/minecraft/util/BlockPos", add.owner);

        List<Integer> slots = intArgumentSlotsBefore(add);
        assertEquals("the three offsets must all reach add as the lambda's own parameters, or this "
                + "test is reading the wrong call", 3, slots.size());
        assertEquals("dx, dy, dz must reach BlockPos.add in DECLARATION order -- slots 2, 3, 4 after "
                + "the two captured references. Feeding them transposed samples the position mirrored "
                + "across x=z while the hit is still emitted at the walk's own offset, so find_block "
                + "answers with coordinates where nothing is: the model then navigates to an empty "
                + "square and digs air, and every retry lands in the same wrong place.",
                List.of(2, 3, 4), slots);

        // The emit half, through the seam. Offset (3,0,1) is asymmetric in x/z on purpose: a
        // symmetric one is unchanged by a transposition and would pin nothing.
        List<BlockFinder.Hit> hits = BlockFinder.search(world(Map.of("3,0,1", "minecraft:iron_ore")),
                100, 64, 200, "iron_ore", 4, 4);
        assertEquals("the ore is found once", 1, hits.size());
        assertEquals("x must be the origin's x plus the walk's dx, so a caller can act on it directly",
                103, hits.get(0).x());
        assertEquals(64, hits.get(0).y());
        assertEquals("and z must be the origin's z plus dz -- 203 here would be x's offset landing on "
                + "z, which is the mirrored position",
                201, hits.get(0).z());
    }

    // ---- reading the sampler lambda's own call site --------------------------

    /**
     * The synthetic method javac compiles {@code find}'s sampler lambda into.
     *
     * <p>Matched on shape rather than on the exact {@code lambda$find$0} name so a reordering of
     * lambdas in the class cannot make this silently pass by finding nothing: it is the body that
     * takes the walk's three ints and answers a name.
     */
    private static MethodNode findSamplerLambda() {
        byte[] bytes = classBytes(BLOCK_FINDER);
        assertNotNull("BlockFinder.class must be readable from the test classpath: the pairing of "
                + "sampled offset to emitted offset is unobservable any other way, since find needs a "
                + "live WorldClient and every seam-level test goes through search()", bytes);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<MethodNode> found = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (m.name.startsWith("lambda$find") && m.desc.endsWith("III)Ljava/lang/String;")) {
                found.add(m);
            }
        }
        assertEquals("exactly one sampler lambda is expected inside find; " + found.size()
                + " candidates would make this assertion ambiguous", 1, found.size());
        return found.get(0);
    }

    private static MethodInsnNode callTo(MethodNode m, String calleeName) {
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode call && call.name.equals(calleeName)) {
                return call;
            }
        }
        return null;
    }

    /**
     * The local-variable slots of the trailing {@code ILOAD}s feeding a call, in argument order.
     *
     * <p>Walks back from the call and stops at the first instruction that is not an {@code ILOAD}, so
     * it reads only the int arguments and never mistakes the receiver push for one.
     */
    private static List<Integer> intArgumentSlotsBefore(AbstractInsnNode call) {
        List<Integer> slots = new ArrayList<>();
        for (AbstractInsnNode n = call.getPrevious(); n != null; n = n.getPrevious()) {
            if (n.getOpcode() < 0) {
                continue;
            }
            if (n.getOpcode() != Opcodes.ILOAD || !(n instanceof VarInsnNode)) {
                break;
            }
            slots.add(0, ((VarInsnNode) n).var);
        }
        return slots;
    }

    /** Raw bytes of a class from the test classpath, or null when absent. */
    private static byte[] classBytes(String internalName) {
        String res = "/" + internalName + ".class";
        try (InputStream in = BlockFinderShellWalkIsPinnedAtItsBoundaryTest.class
                .getResourceAsStream(res)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    // ---- the range the tool advertises for 'limit' ---------------------------

    /** The {@code limit} argument's advertised description, as the model reads it. */
    private static String limitSchemaDescription() {
        ToolRegistry reg = new ToolRegistry(new ToolContext(null, null, null, null, null));
        for (SyncToolSpecification spec : reg.all()) {
            Tool t = spec.tool();
            if (!t.name().equals("find_block")) {
                continue;
            }
            Object props = ((Map<?, ?>) t.inputSchema()).get("properties");
            assertTrue("find_block's schema must expose its properties as a map to derive from",
                    props instanceof Map);
            Object limit = ((Map<?, ?>) props).get("limit");
            assertTrue("find_block must still advertise a 'limit' argument at all",
                    limit instanceof Map);
            return String.valueOf(((Map<?, ?>) limit).get("description"));
        }
        throw new AssertionError("find_block is missing from the registry");
    }
}
