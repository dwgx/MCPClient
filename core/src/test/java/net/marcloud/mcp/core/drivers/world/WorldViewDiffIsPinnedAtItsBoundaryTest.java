package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.io.transport.ToolRegistry;
import org.junit.Test;

/**
 * Six mutations of the world_view capture/diff seam survived the whole suite, and every one of them
 * is a VALUE or a KEY NAME rather than a branch: the existing tests ask whether a section is present
 * and then stop. So a self section shipping the PREVIOUS tick's coordinates, the truncation flag
 * under a different name, and a {@code cleared} list naming exactly the slots that are still full
 * all pass unchallenged.
 *
 * <p>Three of the six also sit on this subsystem's recurring defect family -- "we could not read it"
 * and "there is none" collapsing into one token, already fixed four times over (self.air, effects,
 * entities.left, block-name sentinels). mode=full encodes that split as absent-versus-explicit-null,
 * and the null side of it had no test at all: nothing called {@link WorldViewJson#toMap} on a PRESENT
 * view, so the branch that emits the explicit null could be deleted without a single failure.
 *
 * <p>The produced nulls below are driven through {@link WorldViewCapture} rather than hand-written.
 * A previous round's tests only ever drove the DIFF side and built the null themselves, which is why
 * restoring the original capture defect ({@code : null} becoming {@code : List.of()}) stayed green.
 *
 * <p>Fixtures deliberately mirror {@code WorldViewDiffTest}'s shapes so the two files read against
 * each other: that one pins which keys appear, this one pins what they say.
 */
public class WorldViewDiffIsPinnedAtItsBoundaryTest {

    /** A player standing still at (x,y,z) -- same shape as {@code WorldViewDiffTest#idle}. */
    private static SelfView selfAt(double x, double y, double z) {
        return new SelfView(x, y, z, 0, 0, 0, 0f, 0f, 20f, 20, 5f,
                3, 0.5f, 0, 300, "SURVIVAL", false, false, true, List.of());
    }

    /** Only the self section is populated, so nothing else can put a key in the diff. */
    private static WorldView selfOnly(long tick, SelfView self) {
        return new WorldView(true, tick, "explore", self, null, List.of(), false, null, null, null);
    }

    private static EntityView zombie(int id, double x, Integer hp) {
        return new EntityView(id, "Zombie", x, 64.0, 0.0, Math.abs(x), hp, "Zombie");
    }

    private static WorldView entitiesOnly(long tick, List<EntityView> entities, boolean capped) {
        return new WorldView(true, tick, "explore", null, null, entities, capped, null, null, null);
    }

    private static WorldView inventoryOnly(long tick, InventoryView inv) {
        return new WorldView(true, tick, "explore", null, null, List.of(), false, inv, null, null);
    }

    private static WorldView targetOnly(long tick, TargetView target) {
        return new WorldView(true, tick, "explore", null, null, List.of(), false, null, target, null);
    }

    private static InventoryView inventory(int selectedSlot, InventoryView.Slot... slots) {
        return new InventoryView(selectedSlot, List.of(slots));
    }

    private static TargetView blockTarget(String block, int bx, int by, int bz) {
        return new TargetView("block", block, bx, by, bz, "NORTH", null, null, null, 3.0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    /**
     * Empty rather than null for an absent key, so a regression fails as a value mismatch with a
     * readable message instead of an NPE inside this helper.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> owner, String key) {
        if (owner == null) {
            return List.of();
        }
        List<Object> l = (List<Object>) owner.get(key);
        return l == null ? List.of() : l;
    }

    private static String worldViewDescription() {
        ToolRegistry reg = new ToolRegistry(new ToolContext(null, null, null, null, null));
        for (SyncToolSpecification spec : reg.all()) {
            if (spec.tool().name().equals("world_view")) {
                return spec.tool().description();
            }
        }
        throw new AssertionError("world_view not found");
    }

    // ---- self.pos: the diff fired on the right condition and could still ship the wrong tick ----

    /**
     * {@code self.pos} in mode=diff is WHERE THE PLAYER IS NOW, not the sample the delta was measured
     * from. Nothing asserted the value before this: the neighbouring test asks only
     * {@code containsKey("pos")}, and the reflection sweep that claims every self field is observable
     * in diff mode judges {@code containsKey("self")}, so any stale-value mutation on any self field
     * passed both.
     *
     * <p>All three axes move, so shipping the baseline is caught per-component rather than only in
     * aggregate.
     */
    @Test
    public void theSelfPositionInADiffIsWhereThePlayerIsNowNotWhereItWas() {
        WorldView before = selfOnly(1L, selfAt(10, 64, 0));
        WorldView after = selfOnly(2L, selfAt(12, 70, -4));

        Map<String, Object> d = WorldViewDiff.diff(before, after);
        assertEquals("mode=diff must carry the CURRENT coordinates. The previous tick's would leave a "
                        + "caller navigating on diff output permanently one poll behind, stepping off "
                        + "a ledge it believes is still two blocks away: " + d,
                List.of(12.0, 70.0, -4.0), asMap(d.get("self")).get("pos"));
        assertEquals("and the two modes must describe the same player: a diff whose pos disagrees "
                        + "with mode=full for the same snapshot makes the two payloads unmergeable",
                WorldViewJson.selfMap(after.self()).get("pos"), asMap(d.get("self")).get("pos"));
    }

    // ---- mode=full's three entity states. "Nobody looked" and "nothing is there" must not arrive
    // ---- as the same token; that collapse is the defect this subsystem has now fixed four times.

    /**
     * An unsampled entities section must reach mode=full as an EXPLICIT null.
     *
     * <p>The producer is driven rather than a null being written by hand, because the last round
     * proved a hand-built null cannot catch this class of defect: its tests all entered on the diff
     * side, so restoring {@code entitiesSection}'s original {@code List.of()} stayed green.
     * {@code entitiesSection(null, cap)} is exactly what a poll whose {@code sections} omitted
     * entities produces.
     */
    @Test
    public void fullModeShipsAnUnsampledEntitiesSectionAsAnExplicitNull() {
        List<EntityView> unsampled = WorldViewCapture.entitiesSection(null, 12);
        Map<String, Object> full = WorldViewJson.toMap(entitiesOnly(1L, unsampled, false));

        assertTrue("the key must be PRESENT, because absence is this payload's word for 'nothing "
                        + "nearby': dropping it tells a caller polling with sections=['self'] that it "
                        + "is alone when in fact nobody looked: " + full,
                full.containsKey("entities"));
        assertNull("and the value must be null, the only token that says 'not sampled' -- an empty "
                        + "list here is a claim about the world that no scan supports",
                full.get("entities"));
    }

    /**
     * The counterpart in both directions: a section that WAS sampled must not borrow the failure
     * token, and one that found something must ship it. Without this the fix above could be satisfied
     * by emitting null unconditionally, which would make every quiet poll read as a failed read.
     */
    @Test
    public void fullModeOmitsEntitiesOnlyWhenTheScanLookedAndFoundNothing() {
        Map<String, Object> empty = WorldViewJson.toMap(
                entitiesOnly(1L, WorldViewCapture.entitiesSection(List.of(), 12), false));
        assertFalse("a sampled-but-empty scan is a statement about the world, and absence is the "
                        + "cheap encoding reserved for it: an explicit null here would tell a caller "
                        + "standing in an empty field that its sensors failed: " + empty,
                empty.containsKey("entities"));

        Map<String, Object> populated = WorldViewJson.toMap(entitiesOnly(2L,
                WorldViewCapture.entitiesSection(List.of(zombie(7, 5.0, 20)), 12), false));
        List<Object> rows = list(populated, "entities");
        assertEquals("a scan that found something must ship the rows, or the caller is blind to the "
                        + "mob next to it: " + populated, 1, rows.size());
        assertEquals("carrying the id the caller tracks the mob across polls by",
                7, asMap(rows.get(0)).get("id"));
    }

    // ---- entities.moved fires on hp as well as on movement: a cornered mob that is NOT moving is
    // ---- the case a caller most needs to hear about, and every fixture in the subsystem held hp
    // ---- constant while moving x, so the position clause alone satisfied them all.

    @Test
    public void aStationaryMobTakingDamageIsReportedSoTheCallerLearnsItsHitsAreLanding() {
        WorldView before = entitiesOnly(1L, List.of(zombie(7, 5.0, 20)), false);
        WorldView after = entitiesOnly(2L, List.of(zombie(7, 5.0, 12)), false);

        Map<String, Object> ent = asMap(WorldViewDiff.diff(before, after).get("entities"));
        List<Object> moved = list(ent, "moved");
        assertEquals("hp movement on a mob standing still must reach the caller: mid-fight against a "
                        + "cornered mob, silence here is an empty diff, and the model cannot tell "
                        + "whether its hits are landing or its sword is doing nothing",
                1, moved.size());
        Map<String, Object> row = asMap(moved.get(0));
        assertEquals(7, row.get("id"));
        assertEquals("the row must carry the NEW hp; the value the first poll saw would read as a mob "
                        + "that is absorbing every hit for free", 12, row.get("hp"));
        assertEquals("and its position, unchanged, so the caller still knows where to keep swinging",
                List.of(5.0, 64.0, 0.0), row.get("pos"));
    }

    /**
     * The negative half, taken above the Integer cache on purpose.
     *
     * <p>{@code hp} is boxed, and boxed identity coincides with equality only across -128..127 -- the
     * same trap that made a full-air value of 300 report a change on every single poll. 300 is a real
     * hp in vanilla (the wither's), so comparing it with {@code !=} would put a perfectly still boss
     * in {@code moved} forever.
     */
    @Test
    public void aStationaryMobAtUnchangedHpIsSilentEvenAboveTheIntegerCache() {
        Integer hpFirstPoll = Integer.valueOf(300);
        Integer hpSecondPoll = Integer.valueOf(300);
        WorldView before = entitiesOnly(1L, List.of(zombie(7, 5.0, hpFirstPoll)), false);
        WorldView after = entitiesOnly(2L, List.of(zombie(7, 5.0, hpSecondPoll)), false);

        Map<String, Object> ent = asMap(WorldViewDiff.diff(before, after).get("entities"));
        assertTrue("two identical polls must produce no row: a mob reported as moved every poll while "
                        + "it stands there reads as a fight in progress, and the caller cannot find "
                        + "the real changes among the noise",
                list(ent, "moved").isEmpty());
    }

    // ---- inventory.cleared. The entire per-slot body of inventoryDiff was unreachable by the suite:
    // ---- every fixture in the subsystem passes null for the inventory, so diff() short-circuited.

    @Test
    public void clearedNamesTheSlotThatWasEmptiedAndNotTheOnesStillHoldingItems() {
        InventoryView before = inventory(0,
                new InventoryView.Slot(0, "diamond_pickaxe", 1, 12, 1561),
                new InventoryView.Slot(1, "torch", 8, 0, null),
                new InventoryView.Slot(2, "bread", 1, 0, null));
        // The bread was eaten. 'slots' lists only NON-EMPTY slots, so slot 2 stops appearing at all.
        InventoryView after = inventory(0,
                new InventoryView.Slot(0, "diamond_pickaxe", 1, 12, 1561),
                new InventoryView.Slot(1, "torch", 8, 0, null));

        Map<String, Object> inv = asMap(WorldViewDiff.diff(inventoryOnly(1L, before),
                inventoryOnly(2L, after)).get("inventory"));
        assertEquals("only the emptied slot may be listed. A cleared list naming the slots that still "
                        + "hold the pickaxe and the torches tells the model it just lost its whole kit "
                        + "-- and says nothing about the one item it really did consume: " + inv,
                List.of(2), list(inv, "cleared"));
    }

    /** A slot that still holds something is CHANGED, never cleared, and the count is the new one. */
    @Test
    public void aSlotWhoseCountFellIsReportedAsChangedRatherThanCleared() {
        InventoryView before = inventory(0, new InventoryView.Slot(1, "torch", 8, 0, null));
        InventoryView after = inventory(0, new InventoryView.Slot(1, "torch", 7, 0, null));

        Map<String, Object> inv = asMap(WorldViewDiff.diff(inventoryOnly(1L, before),
                inventoryOnly(2L, after)).get("inventory"));
        List<Object> changed = list(inv, "changed");
        assertEquals("spending one torch must be visible, or a caller cannot see its supplies run "
                        + "down until the slot vanishes: " + inv, 1, changed.size());
        assertEquals("the row carries the count AFTER the change, which is the number a restock "
                        + "decision turns on", 7, asMap(changed.get(0)).get("count"));
        assertTrue("and a slot that still holds seven torches must not appear in cleared, which the "
                        + "model reads as the stack being gone: " + inv,
                list(inv, "cleared").isEmpty());
    }

    // ---- target: the block branch compares coordinates as well as the block NAME, because pos and
    // ---- side are what a dig or a placement is aimed at. Every fixture in the subsystem passed
    // ---- TargetView.miss() or null on both sides, so this branch was never entered.

    @Test
    public void slidingTheCrosshairAlongAWallEmitsTheNewCoordinatesNotSilence() {
        TargetView start = blockTarget("stone", 10, 64, -3);
        // One axis each, so a comparison dropped per-axis is caught as precisely as all three at once.
        for (TargetView cur : List.of(blockTarget("stone", 11, 64, -3),
                blockTarget("stone", 10, 65, -3),
                blockTarget("stone", 10, 64, -2))) {
            Map<String, Object> d = WorldViewDiff.diff(targetOnly(1L, start), targetOnly(2L, cur));
            Map<String, Object> target = asMap(d.get("target"));
            assertTrue("the crosshair moved to a different block of the same type and the diff said "
                            + "nothing. In diff mode a missing key means UNCHANGED, so the model digs "
                            + "or places against the coordinate it saw several polls ago: " + d,
                    target != null);
            assertEquals("and the key must carry the block now under the crosshair, since a stale "
                            + "coordinate aims the next action at the wrong square",
                    List.of(cur.bx(), cur.by(), cur.bz()), target.get("pos"));
            assertEquals("block", target.get("hitType"));
        }
    }

    /** The counterpart: a crosshair that has not moved must stay off the wire. */
    @Test
    public void anUnmovedCrosshairOnTheSameBlockEmitsNoTargetKey() {
        Map<String, Object> d = WorldViewDiff.diff(targetOnly(1L, blockTarget("stone", 10, 64, -3)),
                targetOnly(2L, blockTarget("stone", 10, 64, -3)));
        assertFalse("an unchanged target must be omitted, or the section a caller polls FOR changes "
                        + "reports one on every poll and stops carrying information: " + d,
                d.containsKey("target"));
    }

    // ---- the truncation fact in mode=full, under the key name the tool description promises. The
    // ---- string 'entitiesCapped' occurred nowhere in core/src/test, so the key could be renamed.

    @Test
    public void fullModeCarriesTheTruncationFactUnderTheKeyItsDescriptionNames() {
        Map<String, Object> full = WorldViewJson.toMap(
                entitiesOnly(1L, List.of(zombie(9, 1.0, 20)), true));

        assertEquals("mode=full must flag truncation as 'entitiesCapped'. A caller that branches on "
                        + "the documented key never sees a renamed one, and then reads a TRUNCATED "
                        + "view of its surroundings as a complete one: " + full,
                Boolean.TRUE, full.get("entitiesCapped"));
        assertTrue("the payload and the legend must name the same key, or the description teaches the "
                        + "model to look somewhere the fact never appears",
                worldViewDescription().contains("'entitiesCapped'"));
        assertFalse("'capped' is mode=diff's key for this fact and must not stand in for it here: it "
                        + "would leave every reader following the description blind: " + full,
                full.containsKey("capped"));

        Map<String, Object> untruncated = WorldViewJson.toMap(
                entitiesOnly(2L, List.of(zombie(9, 1.0, 20)), false));
        assertFalse("and a poll that dropped nothing must not raise the flag, or the warning stops "
                        + "distinguishing a truncated view from a complete one: " + untruncated,
                untruncated.containsKey("entitiesCapped"));
    }
}
