package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.io.transport.ToolRegistry;

/**
 * The numbers a {@code world_view} grid column is built from, pinned at the values that make them
 * true rather than at the symbols that name them.
 *
 * <p><b>Why this file exists.</b> Six deliberate one-token edits to {@link LocalGrid} were compiled
 * and run against the whole suite and every one stayed GREEN: the drop probe's 24-block bound cut
 * to 20, the probe's start moved from the feet layer to one below it, {@link LocalGrid#WALK_CLEAR}
 * repointed from vanilla's 1 to vanilla's 0, the floor test switched from a collision box to
 * {@code getMaterial() != null}, and the walk probe shifted a layer up and told to avoid water.
 * Each one changes what the tool tells a model about terrain it is about to walk onto or fall into,
 * and none of them could be observed by the tests that exist -- those reference the constants
 * symbolically, hand-build {@code Column} records with literal values, or drive the arithmetic
 * seam with a start value production never passes.
 *
 * <p><b>Why three of these are asserted on the compiled bytecode.</b> {@code standable},
 * {@code dropDepth} and {@code walkVerdict} are private and need a live {@code WorldClient} (and,
 * for the verdict, an {@code Entity}), so nothing in {@code core/src/test} can call them -- which
 * is precisely how a guard that cannot be false, and a probe aimed at the wrong layer, survived.
 * Reading the argument a call site actually passes is the same technique the {@code Ki*} patch
 * tests use to prove a transform is load-bearing, and it fails on the mutation instead of agreeing
 * with it. The bytecode is required, not assumed present: skipping would let the mutation live.
 *
 * <p>The rest are driven through the seams that do exist -- {@link LocalGrid#dropDepthOf} for the
 * arithmetic, {@link WorldViewJson#gridMap} for the encoding -- and always with LITERAL vanilla
 * values, because an assertion written in terms of the constant under test agrees with any value
 * that constant takes.
 */
public class LocalGridColumnsArePinnedAtTheirBoundaryTest {

    /** Internal name of the class under test, whose own call sites are read below. */
    private static final String LOCAL_GRID = "net/marcloud/mcp/core/drivers/world/LocalGrid";

    @Test
    public void theDropProbeReachesTwentyFourBlocksBelowTheFeetAndNoFurther() {
        // Driven AT the bound and one past it. A shallower probe is not merely coarser: the model
        // is told "deep" means certainly lethal, so every depth that stops being measurable becomes
        // a square the model refuses to step onto.
        assertEquals("a floor 24 blocks below the feet layer must still be MEASURED. The model acts "
                + "on the number: 23 is a fall it can weigh against its own hp, while \"deep\" is "
                + "a route it abandons.",
                Integer.valueOf(23), LocalGrid.dropDepthOf(dy -> dy == -24 ? "stone" : "air", -1));
        assertNull("and one block past the bound must read \"deep\", never a number, because a "
                + "reported depth is a promise the probe actually reached that floor",
                LocalGrid.dropDepthOf(dy -> dy == -25 ? "stone" : "air", -1));
    }

    @Test
    public void theProbeBoundIsTheNumberQuotedToTheModelAndTheFirstCertainlyFatalFall() {
        final int playerMaxHp = 20;       // SharedMonsterAttributes.maxHealth base value
        final int freeFallBlocks = 3;     // vanilla damage is ceil(distance - 3), EntityLivingBase
        int bound = probeBound();

        assertEquals("the probe bound is quoted verbatim to the model as \"its 24-block bound\", so "
                + "the constant and the legend have to be one number. A model told measurement ends "
                + "at 24 while it really ends shallower cannot tell a measured fall it could weigh "
                + "from a \"deep\" it must refuse.",
                24, bound);
        assertTrue("the legend must quote the bound production actually enforces, not a number "
                + "hand-copied beside it",
                worldViewDescription().contains("its " + bound + "-block bound"));

        // Why 24 is the defensible place to stop rather than an arbitrary cap: the deepest fall the
        // probe can still measure is bound-1, and at 24 that is 23 blocks -> 20 HP -> exactly fatal
        // at full health. So "deep" begins exactly where the answer stops changing. Cut the bound
        // to 20 and the deepest measured fall is 19 -> 16 HP, survivable, which makes "certainly
        // lethal" false of the first columns that report it.
        int deepestMeasured = bound - 1;
        assertTrue("the bound must sit where measurement stops mattering: the deepest MEASURED fall "
                + "(" + deepestMeasured + " blocks = " + (deepestMeasured - freeFallBlocks)
                + " HP) has to be fatal at full health (" + playerMaxHp + " HP) already, or \"deep\" "
                + "starts covering falls the model could have survived and would wrongly refuse",
                deepestMeasured - freeFallBlocks >= playerMaxHp);
    }

    @Test
    public void aBlockedSquareReachesTheWireWhileAClearOneIsTheOmittedCase() {
        assertEquals("WALK_CLEAR is the OMISSION KEY: WorldViewJson leaves walk off the wire when it "
                + "equals this constant, and the legend tells the model \"ABSENT means WALKABLE\". "
                + "It must be vanilla's 1 (clear). Point it at 0 and it is vanilla's BLOCKED verdict "
                + "that silently vanishes, so every wall, solid block and closed door reads as "
                + "walkable ground.",
                1, LocalGrid.WALK_CLEAR);

        // Literal vanilla values on purpose. Writing these as LocalGrid.WALK_CLEAR would make the
        // assertions agree with whatever the constant became, which is exactly how the omission key
        // went unpinned while three tests appeared to cover it.
        Map<String, Object> blocked = wireColumn(0);
        assertTrue("vanilla's 0 must be CARRIED on the wire: the legend lists \"0 blocked\" as a "
                + "value the model can receive, and a hazard that is omitted instead is read back "
                + "as walkable footing",
                blocked.containsKey("walk"));
        assertEquals("and it must arrive as the number the legend defines, not as some other token",
                Integer.valueOf(0), blocked.get("walk"));

        assertFalse("vanilla's 1 (clear) must stay OFF the wire: that omission is what \"ABSENT "
                + "means WALKABLE\" describes, and emitting it instead hands the model a bare "
                + "number its legend never lists",
                wireColumn(1).containsKey("walk"));

        String desc = worldViewDescription();
        assertTrue("the legend the model reads must still say absence means walkable",
                desc.contains("ABSENT means WALKABLE"));
        assertTrue("and must still enumerate 0 as blocked, which is only receivable while 0 is not "
                + "the omitted value", desc.contains("0 blocked"));
    }

    @Test
    public void theDropProbeStartsAtTheFeetLayerItselfNotOneBlockBelowIt() {
        MethodNode dropDepth = method("dropDepth");
        AbstractInsnNode call = callTo(dropDepth, "dropDepthOf");
        assertNotNull("dropDepth must delegate to the dropDepthOf seam, or its arithmetic is "
                + "unreachable from any test", call);
        List<Integer> startDy = constantsBefore(call, 1);
        assertEquals("the startDy handed to dropDepthOf must be a compile-time constant this test "
                + "can read", 1, startDy.size());
        assertEquals("production must probe from dy=0. The origin is FLOORED from posY, so a player "
                + "on a bottom slab, lower stairs, a snow layer or a carpet at posY=63.5 has an "
                + "origin of 63 -- which IS the slab. Starting at -1 looks straight past it and "
                + "reports the void underneath: measured live, a slab bridge over a 20-deep drop "
                + "told the agent its own square was a 20-block fall, so it refused to cross the "
                + "bridge it was standing on.",
                Integer.valueOf(0), startDy.get(0));

        // The two start values are not interchangeable, which is what makes the argument above a
        // correctness fact rather than a stylistic one.
        assertEquals("from the feet layer, a floor at dy=0 is one the player already stands on",
                Integer.valueOf(0), LocalGrid.dropDepthOf(dy -> dy == 0 ? "stone_slab" : "air", 0));
        assertNull("while starting one block lower misses that same floor and reports a bottomless "
                + "column: the reading that stranded the agent on the bridge",
                LocalGrid.dropDepthOf(dy -> dy == 0 ? "stone_slab" : "air", -1));
    }

    @Test
    public void aFloorIsDecidedByVanillasCollisionBoxNotByAMaterialOrTheFullCubeFlag() {
        MethodNode standable = method("standable");
        assertTrue("the floor test must ask Block.getCollisionBoundingBox. It is the only question "
                + "that separates a floor from scenery, and every alternative is a guard that "
                + "cannot be false: Block.getMaterial() is non-null for every block including air, "
                + "so a material test finds a \"floor\" at dy=0 in every column and reports solid "
                + "footing over an open void.",
                calls(standable, "getCollisionBoundingBox"));
        assertFalse("and it must not lean on isFullCube: vanilla's Block.isFullCube returns true "
                + "UNCONDITIONALLY and BlockAir does not override it, so a solidity test built on it "
                + "calls open air solid. That is the same defect shape this repo already paid for "
                + "once, where a floor check could not fail even standing over a pit.",
                calls(standable, "isFullCube"));
        assertTrue("liquids must still count as a floor, because the fall genuinely ends there and "
                + "the caller needs the depth either way: water breaks the fall, lava ends the run, "
                + "and which one it is comes from 'surface'",
                calls(standable, "isLiquid"));
    }

    @Test
    public void theWalkVerdictJudgesTheSquareAtFeetHeightNotTheLayerAboveIt() {
        MethodNode walkVerdict = method("walkVerdict");
        assertNotNull("walkVerdict must delegate to vanilla's own passability verdict rather than "
                + "invent a second taxonomy", callTo(walkVerdict, "func_176170_a"));

        // func_176170_a scans j in [y, y+sizeY), so the y it is handed IS the bottom of the volume
        // it judges. Exactly two integer offsets are legitimate in this method, one per horizontal
        // axis (+dx, +dz); the entity-size arithmetic is float. A third means the vertical origin
        // moved as well.
        assertEquals("walkVerdict must offset the column horizontally only and pass the origin's own "
                + "Y. Vanilla scans UPWARD from the y it is given, so raising it by one asks about "
                + "the volume above the player: a solid block, a fence or lava AT the square the "
                + "player would stand in stops being reported at all (the column reads clear, so it "
                + "is omitted, so it reads walkable) while something harmless over head height "
                + "starts reporting blocked. Every column would then describe a volume the player "
                + "does not occupy.",
                2, countOpcode(walkVerdict, Opcodes.IADD));

        AbstractInsnNode yRead = callTo(walkVerdict, "getY");
        assertNotNull("walkVerdict must read the origin's Y", yRead);
        assertNull("the origin's Y must reach vanilla unmodified, not through a constant offset",
                intConstant(nextReal(yRead)));

        assertTrue("and the promise being kept here is the one the model actually reads",
                worldViewDescription().contains("1x2 volume at your"));
    }

    @Test
    public void theWalkVerdictNeverAsksVanillaToAvoidWaterSoMinusOneStaysUnreachable() {
        MethodNode walkVerdict = method("walkVerdict");
        AbstractInsnNode call = callTo(walkVerdict, "func_176170_a");
        assertNotNull("walkVerdict must delegate to vanilla's own passability verdict", call);

        List<Integer> flags = constantsBefore(call, 3);
        assertEquals("the three trailing flags (avoidWater, breakDoors, enterDoors) must be "
                + "compile-time constants this test can read, since they decide the whole value "
                + "domain of 'walk'", 3, flags.size());
        assertEquals("avoidWater must be false. With it true, WalkNodeProcessor returns -1 for any "
                + "water column, and -1 is documented nowhere in the tool's legend -- the model "
                + "receives an unmapped code for standing in water and can only guess whether that "
                + "means passable. False keeps water at 2, which the legend does define.",
                Integer.valueOf(0), flags.get(0));
        assertEquals("breakDoors must be false: the agent is not promised a verdict that assumes it "
                + "will break through", Integer.valueOf(0), flags.get(1));
        assertEquals("enterDoors must be false, which is what makes the legend's \"0 blocked (solid "
                + "or a door)\" true of a wooden door", Integer.valueOf(0), flags.get(2));

        // The legend and the flags are one claim: it is only complete while the flags keep the
        // unlisted verdicts unreachable.
        String legend = walkLegend();
        assertTrue("water must be documented as the clear-but-wet verdict 2, which is only what "
                + "vanilla returns while avoidWater is false",
                legend.contains("2 clear-but-in-water"));
        assertTrue("and the hazard verdicts the flags do leave reachable must be listed",
                legend.contains("-2 lava") && legend.contains("-3 fence"));
        assertFalse("while -1 must stay out of the legend: a value the model can receive but cannot "
                + "look up is worse than no value at all", legend.contains("-1"));
    }

    // ---- seam-driven helpers ------------------------------------------------

    /**
     * The deepest layer below the feet at which the probe still finds a floor, derived rather than
     * read: a reflective read of the constant would agree with a legend that had drifted from it.
     */
    private static int probeBound() {
        for (int depth = 1; depth <= 256; depth++) {
            final int floorAt = -depth;
            if (LocalGrid.dropDepthOf(dy -> dy == floorAt ? "stone" : "air", -1) == null) {
                return depth - 1;
            }
        }
        throw new AssertionError("the drop probe found a floor 256 blocks down, so it is effectively "
                + "unbounded -- it runs at every one of the (2r+1)^2 columns, which is the cost the "
                + "bound exists to cap");
    }

    /** One column carrying a RAW vanilla verdict, encoded exactly as {@code world_view} sends it. */
    private static Map<String, Object> wireColumn(int rawWalkVerdict) {
        LocalGrid.Column c = new LocalGrid.Column(0, 0, 0, "stone", "air", "air", List.of(), 0,
                rawWalkVerdict);
        LocalGrid g = new LocalGrid(1, "surface", 0, 64, 0, List.of(c), Map.of("stone", 1));
        return asMap(((List<?>) WorldViewJson.gridMap(g).get("columns")).get(0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static String worldViewDescription() {
        ToolRegistry reg = new ToolRegistry(new ToolContext(null, null, null, null, null));
        for (SyncToolSpecification spec : reg.all()) {
            if (spec.tool().name().equals("world_view")) {
                return spec.tool().description();
            }
        }
        throw new AssertionError("world_view is missing from the registry");
    }

    /**
     * The enumerated walk values as the model reads them, cut out of the surrounding prose so a
     * search for a value cannot match an unrelated number elsewhere in a 4 KB description -- the
     * hollow-substring shape this repo has already caught in itself.
     */
    private static String walkLegend() {
        String desc = worldViewDescription();
        int from = desc.indexOf("the values are");
        int to = desc.indexOf("It judges");
        assertTrue("world_view must enumerate the walk values before explaining the volume they "
                + "describe, or there is no legend to check", from >= 0 && to > from);
        return desc.substring(from, to);
    }

    // ---- reading the call sites of the three private, world-bound methods ----

    /**
     * One method of the compiled {@link LocalGrid}. Asserted present rather than assumed: this is
     * our own production class, always on the test classpath, so a missing body is a broken build
     * and not an environment to skip over -- skipping is what would let a bad argument survive.
     */
    private static MethodNode method(String name) {
        byte[] bytes = classBytes(LOCAL_GRID);
        assertNotNull("LocalGrid.class must be readable from the test classpath: the arguments its "
                + "private, world-bound methods pass are unobservable any other way", bytes);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name)) {
                return m;
            }
        }
        throw new AssertionError("LocalGrid." + name + " is gone, so the guard it carried is gone "
                + "with it");
    }

    private static AbstractInsnNode callTo(MethodNode m, String calleeName) {
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode call && call.name.equals(calleeName)) {
                return call;
            }
        }
        return null;
    }

    private static boolean calls(MethodNode m, String calleeName) {
        return callTo(m, calleeName) != null;
    }

    private static int countOpcode(MethodNode m, int opcode) {
        int n = 0;
        for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
            if (i.getOpcode() == opcode) {
                n++;
            }
        }
        return n;
    }

    /** The next real instruction, stepping over labels and frames (all of which have opcode -1). */
    private static AbstractInsnNode nextReal(AbstractInsnNode from) {
        AbstractInsnNode n = from.getNext();
        while (n != null && n.getOpcode() < 0) {
            n = n.getNext();
        }
        return n;
    }

    /**
     * The int a single instruction pushes, in any of the forms javac emits, or null when the
     * instruction is not a constant push at all.
     */
    private static Integer intConstant(AbstractInsnNode n) {
        if (n == null) {
            return null;
        }
        int op = n.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
            return op - Opcodes.ICONST_0;
        }
        if (n instanceof IntInsnNode i && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) {
            return i.operand;
        }
        if (n instanceof LdcInsnNode l && l.cst instanceof Integer v) {
            return v;
        }
        return null;
    }

    /**
     * The last {@code howMany} constant arguments a call receives, in argument order.
     *
     * <p>Walks back from the call and stops at the first instruction that is not a constant push,
     * so it reads only the trailing literals and never mistakes a computed argument for one.
     */
    private static List<Integer> constantsBefore(AbstractInsnNode call, int howMany) {
        List<Integer> found = new ArrayList<>();
        for (AbstractInsnNode n = call.getPrevious(); n != null && found.size() < howMany;
                n = n.getPrevious()) {
            if (n.getOpcode() < 0) {
                continue;
            }
            Integer v = intConstant(n);
            if (v == null) {
                break;
            }
            found.add(0, v);
        }
        return found;
    }

    /** Raw bytes of a class from the test classpath, or null when absent. */
    private static byte[] classBytes(String internalName) {
        String res = "/" + internalName + ".class";
        try (InputStream in = LocalGridColumnsArePinnedAtTheirBoundaryTest.class
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
}
