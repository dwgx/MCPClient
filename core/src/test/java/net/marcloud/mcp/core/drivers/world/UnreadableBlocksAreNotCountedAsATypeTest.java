package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import java.util.TreeSet;
import org.junit.BeforeClass;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.item.Item;

/**
 * A block the registry could not read must not be spellable as a block that was read.
 *
 * <p><b>The defect.</b> {@code LocalGrid.idName} and {@code WorldScanner.blockName} both answered
 * {@code "unknown"} when a position could not be resolved, and both then merged that answer into
 * their block-type histogram. So an unreadable column grew a phantom entry -- {@code blockCounts}
 * reporting {@code unknown: 12} for a block type that no {@code find_block} could ever locate, and a
 * column claiming {@code surface: "unknown"} at a height, which is a positive statement about
 * terrain that was never observed. Nothing on the wire distinguished it from a lowercase registry
 * name, and no assertion covered it either way: four mutations of those two methods survived the
 * whole 947-test suite.
 *
 * <p><b>Why this is the fourth instance of one shape.</b> {@code self.air} (a real {@code -1} while
 * drowning vs unreadable), {@code effects} (empty vs unread), {@code entities.left} (unsampled vs
 * gone) were each fixed for the same reason. {@code WorldViewJson} states the rule while emitting
 * {@code walk}: "we could not ask" and a real answer must not collapse into the same token. These
 * two methods broke it three fields earlier in the same loop, and {@code LocalGrid.standable}
 * -- thirty lines from {@code idName} -- had always handled the identical failure in the safe
 * direction and said so in its comment. Two policies for one failure inside one file.
 *
 * <p>These are pure assertions over the sentinel and the census predicate; that the live samplers
 * return the sentinel is verified against a real client, since both need a {@code WorldClient}.
 */
public class UnreadableBlocksAreNotCountedAsATypeTest {

    @BeforeClass
    public static void bootRegistries() {
        // Real registries, because the claim below is about what real names can contain. A
        // hand-listed set of names would only prove the test author agrees with themselves.
        net.minecraft.init.Bootstrap.register();
    }

    /**
     * The sentinel must be unspellable as a registry name -- checked against every registered name,
     * not against a remembered claim about them.
     *
     * <p>This is what makes {@code "?"} correct and {@code "unknown"} wrong. Both are absent from
     * the registry today, so mere absence is too weak a property: {@code "unknown"} is drawn from
     * the same alphabet real names use, so it is a name that merely happens not to be taken, while
     * {@code "?"} cannot be one.
     */
    @Test
    public void theSentinelCannotBeMistakenForARegistryName() {
        TreeSet<Character> alphabet = new TreeSet<>();
        int blocks = 0;
        for (Block b : Block.blockRegistry) {
            Object loc = Block.blockRegistry.getNameForObject(b);
            if (loc == null) {
                continue;
            }
            blocks++;
            for (char c : strip(loc.toString()).toCharArray()) {
                alphabet.add(c);
            }
        }
        int items = 0;
        for (Item it : Item.itemRegistry) {
            Object loc = Item.itemRegistry.getNameForObject(it);
            if (loc == null) {
                continue;
            }
            items++;
            for (char c : strip(loc.toString()).toCharArray()) {
                alphabet.add(c);
            }
        }

        assertTrue("Bootstrap must actually populate the block registry, or every claim below is "
                + "vacuous -- an empty alphabet accepts any sentinel", blocks > 100);
        assertTrue("and the item registry too", items > 100);

        boolean anyCharShared = false;
        for (char c : LocalGrid.NAME_UNREADABLE.toCharArray()) {
            anyCharShared |= alphabet.contains(c);
        }
        assertFalse("the unreadable sentinel " + LocalGrid.NAME_UNREADABLE + " must use at least one "
                + "character no registry name can contain, so it cannot be read as a block that was "
                + "successfully resolved. Registry alphabet across " + blocks + " blocks and " + items
                + " items is " + alphabet + ". A sentinel drawn from that alphabet -- \"unknown\" was "
                + "the one this replaced -- is only untaken, not unspellable.", anyCharShared);
    }

    /**
     * A failed reading must not be tallied as a block type.
     *
     * <p>The histogram is the reason this matters more than a cosmetic naming choice: a caller is
     * told never to conclude a type is ABSENT from {@code blockCounts}, but nothing warned that a
     * PRESENT count could be a fiction, and a phantom key survives every attempt to act on it.
     */
    @Test
    public void theCensusExcludesUnreadablePositions() {
        assertFalse("an unreadable position is not a block type",
                LocalGrid.countable(LocalGrid.NAME_UNREADABLE));
        assertFalse("air is not counted either; it is most of the volume",
                LocalGrid.countable("air"));
        assertFalse("and an absent reading is not a type", LocalGrid.countable(null));
        assertTrue("a real block still counts", LocalGrid.countable("stone"));
        assertTrue("including one whose name merely mentions the sentinel's meaning",
                LocalGrid.countable("unknown_ore"));
    }

    /**
     * The old spelling must not come back, in either sampler.
     *
     * <p>Named explicitly because {@code "unknown"} is still the right answer for {@code biome},
     * {@code dimension} and {@code gamemode} in this same package -- those are not registry names
     * offered back to the caller as something to dig or find, so this is a claim about block names
     * specifically rather than a ban on the word.
     */
    @Test
    public void theSentinelIsNotAWordFromTheRegistryAlphabet() {
        assertFalse("a block-name sentinel must not be a lowercase word: that is exactly what a "
                + "registry name looks like", LocalGrid.NAME_UNREADABLE
                .equals(LocalGrid.NAME_UNREADABLE.toLowerCase(Locale.ROOT))
                && LocalGrid.NAME_UNREADABLE.chars().allMatch(Character::isLetter));
        assertEquals("and it should be the same token walk already uses for 'could not be obtained', "
                + "so the payload has one spelling for that idea rather than two", "?",
                LocalGrid.NAME_UNREADABLE);
    }

    /**
     * A registry that has no name for the block answers with the sentinel, not with a word.
     *
     * <p>This branch is the one the mutations walked straight through. It used to live inline in two
     * private samplers that each need a {@code WorldClient}, so nothing could reach it: replacing its
     * answer with {@code "unknown"} in either sampler left all 947 tests green. It is a seam now for
     * exactly that reason.
     */
    @Test
    public void aBlockTheRegistryCannotNameIsTheSentinelRatherThanAWord() {
        assertEquals("an unnamed block must not be given a name-shaped answer",
                LocalGrid.NAME_UNREADABLE, LocalGrid.wireName(null));
    }

    /**
     * The grid's histogram itself, driven end to end -- not just the predicate behind it.
     *
     * <p>Written because testing the predicate alone was not enough: with the merge inline in
     * {@code sampleColumnar}, swapping {@code countable} back for the old
     * {@code !"air".equals(surface)} condition -- the exact defect -- kept all 947 tests green. The
     * predicate had coverage and its only call site had none, so this drives the census.
     */
    @Test
    public void theGridHistogramCountsSurfacesAndSkipsUnreadableOnes() {
        var counts = LocalGrid.census(java.util.List.of(
                column("stone"), column("stone"), column("air"),
                column(LocalGrid.NAME_UNREADABLE), column(LocalGrid.NAME_UNREADABLE),
                column("iron_ore")));
        assertEquals("two stone surfaces", Integer.valueOf(2), counts.get("stone"));
        assertEquals("one iron_ore surface", Integer.valueOf(1), counts.get("iron_ore"));
        assertFalse("air must not be a histogram key", counts.containsKey("air"));
        assertFalse("and two unreadable columns must not become a block type with count 2 -- that "
                + "phantom key is the defect this whole file exists for",
                counts.containsKey(LocalGrid.NAME_UNREADABLE));
        assertEquals("so the histogram holds exactly the two real types", 2, counts.size());
    }

    /** The volume census in {@code scan_surroundings}, which had the identical defect. */
    @Test
    public void theVolumeCensusAlsoSkipsUnreadablePositions() {
        // A 3x3x3 cube (r=1) where the centre is unreadable and one corner is stone.
        var counts = WorldScanner.census((dx, dy, dz) -> {
            if (dx == 0 && dy == 0 && dz == 0) {
                return LocalGrid.NAME_UNREADABLE;
            }
            return dx == 1 && dy == 1 && dz == 1 ? "stone" : "air";
        }, 1);
        assertEquals("the one real block is counted", Integer.valueOf(1), counts.get("stone"));
        assertFalse("the unreadable centre must not appear as a block type",
                counts.containsKey(LocalGrid.NAME_UNREADABLE));
        assertFalse("nor air, which is 25 of the 27 positions", counts.containsKey("air"));
        assertEquals(1, counts.size());
    }

    private static LocalGrid.Column column(String surface) {
        return new LocalGrid.Column(0, 0, 0, surface, "air", "air", java.util.List.of(), 0,
                LocalGrid.WALK_CLEAR);
    }

    /** And a name that does resolve keeps its meaning, stripped exactly once. */
    @Test
    public void aResolvedNameIsStrippedOfItsNamespaceAndNothingElse() {
        assertEquals("stone", LocalGrid.wireName("minecraft:stone"));
        assertEquals("a name with no namespace is already in wire form",
                "stone", LocalGrid.wireName("stone"));
        assertEquals("only the FIRST colon separates a namespace, so the remainder is preserved "
                + "verbatim rather than being split again", "weird:name",
                LocalGrid.wireName("mod:weird:name"));
    }

    /**
     * Every real registry name survives the round trip into {@code find_block}'s matcher.
     *
     * <p>{@code world_view} tells the caller a name it reads "can be fed straight back", and six
     * separate copies of the namespace rule stood behind that promise. Measured here across every
     * registered block rather than argued from reading them: this is what retires that item from the
     * unverified list.
     */
    @Test
    public void everyRegistryNameSurvivesTheRoundTripIntoTheMatcher() {
        int checked = 0;
        for (Block b : Block.blockRegistry) {
            Object loc = Block.blockRegistry.getNameForObject(b);
            if (loc == null) {
                continue;
            }
            String emitted = LocalGrid.wireName(loc);
            // Air is the ONE documented exception, and the first version of this test asserted it
            // away -- find_block drops air from the query itself, deliberately, since asking for it
            // would match most of the volume. Kept as an explicit case below rather than as a
            // silent skip, because a skip is how an exception quietly becomes a defect.
            if ("air".equals(emitted)) {
                continue;
            }
            checked++;
            assertTrue("world_view promises a name it emits can be fed back to find_block, but "
                    + emitted + " does not match itself", BlockFinder.matches(emitted, emitted));
            assertTrue("and the qualified form must be accepted too, since the caller may copy "
                    + "either: " + loc, BlockFinder.matches(emitted, loc.toString()));
        }
        assertTrue("the registry must be populated or this proves nothing", checked > 100);
    }

    /**
     * The one name world_view emits that find_block will NOT take back.
     *
     * <p>Both statements live in the {@code find_block} description -- "a name read out of a
     * world_view can be fed straight back" and "air is never matched" -- and for {@code air} they
     * contradict each other. Harmless in practice (asking where air is has no use, and the answer is
     * empty rather than wrong), but pinned here so the exception is a known one instead of a
     * surprise, and so that widening it later has to pass through this assertion.
     */
    @Test
    public void airIsTheDocumentedExceptionToThatRoundTrip() {
        assertFalse("air must stay unmatchable: it would match most of the volume",
                BlockFinder.matches("air", "air"));
        assertTrue("while a name that merely starts with those letters is not the exception",
                BlockFinder.matches("air_block_that_does_not_exist", "air_block_that_does_not_exist"));
    }

    private static String strip(String raw) {
        int colon = raw.indexOf(':');
        return colon >= 0 ? raw.substring(colon + 1) : raw;
    }
}
