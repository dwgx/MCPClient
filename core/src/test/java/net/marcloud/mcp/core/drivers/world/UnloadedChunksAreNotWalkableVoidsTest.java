package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.junit.BeforeClass;

/**
 * A chunk nobody has loaded must not be reported as terrain.
 *
 * <p>Vanilla's {@code World.getBlockState} answers AIR for a position it cannot see -- an invalid
 * position at {@code World:850-861}, an unloaded chunk through {@code blankChunk} at
 * {@code ChunkProviderClient:86} -- so "there is nothing here" and "I could not look here" arrive as
 * the same value. {@link net.marcloud.mcp.core.util.BlockProbe} was written for exactly that rule
 * ("the chunk check comes FIRST ... asking getBlockState before it would already have produced the
 * air that has to be distinguished"), and three of its callers did not follow it: the grid's
 * {@code idName} and {@code standable}, and the volume census's {@code blockName}.
 *
 * <p>The consequence was a column that read as a walkable, bottomless void: {@code surface:"air"}
 * with no {@code surfaceDy}, {@code drop:"deep"} -- the token the tool's own legend defines as
 * "certainly lethal" -- and no {@code walk} key, which the same legend defines as WALKABLE. All of
 * that asserted about ground nobody had read.
 *
 * <p>The world below is a real {@code WorldClient} with no chunks in it, which is the production
 * state exactly: {@code isBlockLoaded} false, {@code getBlockState} air. The readers are driven
 * against it directly (reflectively, since they are private and world-bound) and the sampler is
 * driven end to end, so the fix is pinned by behaviour rather than by the presence of a call.
 */
public final class UnloadedChunksAreNotWalkableVoidsTest {

    private static final String LOCAL_GRID = "net/marcloud/mcp/core/drivers/world/LocalGrid";
    private static final String WORLD_SCANNER = "net/marcloud/mcp/core/drivers/world/WorldScanner";

    private static final WorldSettings SETTINGS = new WorldSettings(0L,
            WorldSettings.GameType.SURVIVAL, false, false, WorldType.DEFAULT);

    /**
     * A real client world with NO chunks loaded -- the state the readers have to survive.
     *
     * <p>Built through the real constructor rather than by blanking fields, so
     * {@code isBlockLoaded} is overridden at its own boundary and everything else is vanilla: the
     * inherited {@code getBlockState} answers air through {@code ChunkProviderClient.blankChunk},
     * which is what makes this fixture the defect rather than a simulation of it.
     */
    private static final class UnloadedWorld extends WorldClient {

        UnloadedWorld() {
            super(null, SETTINGS, 0, EnumDifficulty.PEACEFUL, new Profiler());
        }

        @Override
        public boolean isBlockLoaded(BlockPos pos) {
            return false;
        }
    }

    /** A loaded world holding one floor block, so the readers' normal path is exercised too. */
    private static final class FloorAt extends WorldClient {

        private final BlockPos floor;

        FloorAt(BlockPos floor) {
            super(null, SETTINGS, 0, EnumDifficulty.PEACEFUL, new Profiler());
            this.floor = floor;
        }

        @Override
        public boolean isBlockLoaded(BlockPos pos) {
            return true;
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            return floor.equals(pos) ? Blocks.stone.getDefaultState() : Blocks.air.getDefaultState();
        }
    }

    // ===== the readers themselves =====

    /**
     * Vanilla's registries must exist before any {@code World} is constructed.
     *
     * <p>{@code Blocks}' static initialiser throws {@code "Accessed Blocks before Bootstrap!"}
     * ({@code client/.../init/Blocks.java:252}) when {@code Bootstrap.isRegistered()} is false, and
     * because a failed static initialiser re-throws on every later touch, that ONE missing call
     * reported as five unrelated failures: the first a {@code RuntimeException}, the rest
     * {@code ExceptionInInitializerError}. The sibling world tests
     * ({@code UnreadableBlocksAreNotCountedAsATypeTest}, {@code APartialInventoryReadIsNotAnInventoryTest})
     * make this call, which is the entire difference between them passing and this file failing.
     *
     * <p>It is {@code @BeforeClass}, not {@code @Before}: the registries are process-wide and
     * registering twice is what the guard is protecting against.
     */
    @org.junit.BeforeClass
    public static void registerVanillaRegistries() {
        net.minecraft.init.Bootstrap.register();
    }

    /**
     * The reader the grid's surface/feet/head come from must answer UNREAD for a chunk it cannot
     * see, rather than pass vanilla's air along as a reading.
     */
    @Test
    public void theGridNameReaderAnswersUnreadRatherThanAirForAnUnloadedChunk() throws Exception {
        Method idName = LocalGrid.class.getDeclaredMethod("idName", WorldClient.class, BlockPos.class);
        idName.setAccessible(true);

        Object name = idName.invoke(null, new UnloadedWorld(), new BlockPos(0, 64, 0));

        assertEquals("air is a claim that a position was READ and found empty, and the grid legend "
                + "reserves it for exactly that; the unreadable sentinel is the answer for a chunk "
                + "nobody can see", LocalGrid.NAME_UNREADABLE, name);
    }

    /** The same reader, one tool over: {@code scan_surroundings}' census goes through this one. */
    @Test
    public void theScannerNameReaderAnswersUnreadRatherThanAirForAnUnloadedChunk() throws Exception {
        Method blockName = WorldScanner.class.getDeclaredMethod("blockName", WorldClient.class,
                BlockPos.class);
        blockName.setAccessible(true);

        Object name = blockName.invoke(null, new UnloadedWorld(), new BlockPos(0, 64, 0));

        assertEquals("an air answer from an unloaded chunk is a volume census of terrain nobody "
                + "looked at", LocalGrid.NAME_UNREADABLE, name);
    }

    /** And the reader must still read a real world: a fix that answered "unread" always is useless. */
    @Test
    public void aLoadedChunkStillReadsItsBlocks() throws Exception {
        Method idName = LocalGrid.class.getDeclaredMethod("idName", WorldClient.class, BlockPos.class);
        idName.setAccessible(true);

        assertEquals("stone", idName.invoke(null, new FloorAt(new BlockPos(0, 62, 0)),
                new BlockPos(0, 62, 0)));
        assertEquals("air", idName.invoke(null, new FloorAt(new BlockPos(0, 62, 0)),
                new BlockPos(0, 70, 0)));
    }

    /**
     * The ordering, not just the presence.
     *
     * <p>{@code BlockProbe}'s javadoc is explicit that the chunk check has to come first, because a
     * read that happens first has already produced the air that has to be distinguished -- no
     * amount of inspecting the result afterwards can recover which kind of air it was. So the
     * call-site order is the rule, and it is read off the compiled bodies the way
     * {@code LocalGridColumnsArePinnedAtTheirBoundaryTest} reads the other world-bound arguments.
     */
    @Test
    public void everyBlockReaderAsksWhetherTheChunkIsLoadedBeforeReadingIt() {
        // The read each body performs: the grid's two readers call getBlockState directly, while
        // walkVerdict hands the whole question to vanilla's passability probe.
        assertTrue("LocalGrid.idName must ask isBlockLoaded BEFORE getBlockState -- a read that "
                + "happens first has already manufactured the air the check exists to disbelieve",
                loadedCheckComesFirst(LOCAL_GRID, "idName", "getBlockState"));
        assertTrue("LocalGrid.standable is the same rule at the floor probe",
                loadedCheckComesFirst(LOCAL_GRID, "standable", "getBlockState"));
        assertTrue("LocalGrid.walkVerdict must not ask vanilla to judge a chunk nobody can see: its "
                + "verdict would be computed over the manufactured air, and 'clear' is one of the "
                + "answers",
                loadedCheckComesFirst(LOCAL_GRID, "walkVerdict", "func_176170_a"));
        assertTrue("WorldScanner.blockName is the same reader in the other observation tool",
                loadedCheckComesFirst(WORLD_SCANNER, "blockName", "getBlockState"));
    }

    // ===== the column, sampled from an unloaded world =====

    /**
     * The defect itself, driven end to end: sample a column out of a world with no chunks in it and
     * ask what a model would be told.
     */
    @Test
    public void anUnloadedColumnIsNotReportedAsWalkableAirOverAVoid() {
        UnloadedWorld world = new UnloadedWorld();
        LocalGrid grid = LocalGrid.sampleColumnar(world, new BlockPos(0, 64, 0), 1,
                ObserveProfile.SPARSE, null);
        // The entity is null on purpose, and that is the honest input: LocalGrid.walkVerdict
        // answers WALK_UNKNOWN for a null entity, so this column cannot reach "clear" by the
        // accident of a well-formed probe. The positive half below passes a real one, so the
        // pair proves the reader measures when it can and says so when it cannot.

        LocalGrid.Column c = column(grid, 0, 0);
        assertEquals("the column's terrain is UNREAD, not air: air would be a claim about ground "
                + "nobody read, and the grid legend already gives \"?\" a meaning for exactly this",
                LocalGrid.NAME_UNREADABLE, c.surface());
        assertEquals("and vanilla's passability verdict must not be computed over the air "
                + "getBlockState manufactured -- an OMITTED walk key is the legend's word for "
                + "walkable", LocalGrid.WALK_UNKNOWN, c.walk());
        assertNull("the probe observed no floor, and must not report one", c.dropDepth());

        Map<String, Object> wire = wireColumn(grid, 0, 0);
        assertEquals("the verdict reaches the wire as \"?\", never as the omission that means "
                + "walkable", "?", wire.get("walk"));
        assertFalse("THE DEFECT: a drop of \"deep\" WITH NO walk key is the walkable, bottomless "
                + "void -- the combination the audit found, positively asserted about ground nobody "
                + "read: " + wire,
                "deep".equals(wire.get("drop")) && !wire.containsKey("walk"));
    }

    /**
     * The negative half. A loaded column must still measure, or the fix would be a sensor that
     * answers "unknown" everywhere -- strictly worse than the one it replaced.
     */
    @Test
    public void aLoadedColumnStillMeasuresItsFloor() {
        FloorAt world = new FloorAt(new BlockPos(0, 62, 0));
        LocalGrid grid = LocalGrid.sampleColumnar(world, new BlockPos(0, 64, 0), 1,
                ObserveProfile.SPARSE, new net.minecraft.entity.item.EntityItem(world));

        LocalGrid.Column c = column(grid, 0, 0);
        assertEquals("a readable column still reads", "air", c.surface());
        assertEquals("and the probe still measures the fall to the floor two blocks down",
                Integer.valueOf(1), c.dropDepth());
    }

    // ===== helpers =====

    private static LocalGrid.Column column(LocalGrid grid, int dx, int dz) {
        for (LocalGrid.Column c : grid.columns()) {
            if (c.dx() == dx && c.dz() == dz) {
                return c;
            }
        }
        throw new AssertionError("no column at " + dx + "," + dz);
    }

    private static Map<String, Object> wireColumn(LocalGrid grid, int dx, int dz) {
        for (Object o : (List<?>) WorldViewJson.gridMap(grid).get("columns")) {
            Map<String, Object> m = asMap(o);
            if (Integer.valueOf(dx).equals(m.get("dx")) && Integer.valueOf(dz).equals(m.get("dz"))) {
                return m;
            }
        }
        throw new AssertionError("no wire column at " + dx + "," + dz);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    /** Whether the first {@code isBlockLoaded} call precedes the first call that reads a block. */
    private static boolean loadedCheckComesFirst(String internalName, String methodName, String read) {
        MethodNode m = method(internalName, methodName);
        int loaded = indexOfCall(m, "isBlockLoaded");
        int readAt = indexOfCall(m, read);
        return loaded >= 0 && readAt >= 0 && loaded < readAt;
    }

    private static int indexOfCall(MethodNode m, String callee) {
        int i = 0;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext(), i++) {
            if (n instanceof MethodInsnNode call && call.name.equals(callee)) {
                return i;
            }
        }
        return -1;
    }

    /** One method of a compiled class, asserted present: a missing body is a guard that is gone. */
    private static MethodNode method(String internalName, String methodName) {
        byte[] bytes = classBytes(internalName);
        assertNotNull(internalName + ".class must be readable from the test classpath: the order of "
                + "its world-bound calls is unobservable any other way", bytes);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodNode m : cn.methods) {
            if (m.name.equals(methodName)) {
                return m;
            }
        }
        throw new AssertionError(internalName + "." + methodName + " is gone, so the guard it "
                + "carried is gone with it");
    }

    private static byte[] classBytes(String internalName) {
        try (InputStream in = UnloadedChunksAreNotWalkableVoidsTest.class
                .getResourceAsStream("/" + internalName + ".class")) {
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
