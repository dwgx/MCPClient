package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.io.transport.ToolRegistry;
import net.minecraft.item.Item;
import org.junit.Test;

/**
 * Sibling of {@link GridSemanticsAreDocumentedTest}, same defect shape (a tool EMITS a number
 * whose meaning reaches the model only through Java) applied to the two payload sections that
 * carry the numbers a model acts on hardest: inventory wear and the crosshair target.
 *
 * <p>Both were emitted with no legend at all. {@code damage} is signed the opposite way from the
 * durability bar the model has seen in every screenshot of the game -- it counts WEAR UP, so
 * {@code damage:1520 / maxDamage:1561} is 41 uses from breaking, and a model reading it as
 * "remaining" concludes the pickaxe is fine and mines until it snaps. Worse, the same key is
 * vanilla's variant METADATA on anything non-damageable ({@code itemDamage} backs BOTH
 * {@code getItemDamage()} and {@code getMetadata()}, ItemStack:268-275), so the field cannot even
 * be read without first checking whether {@code maxDamage} came along. {@code target.distance}
 * meanwhile has a per-{@code hitType} origin -- eye-to-hitVec for a block, feet-to-feet for an
 * entity, hardcoded 0.0 for a miss -- and "is this in reach" is exactly the question a model asks
 * of it.
 *
 * <p>Vocabulary is DERIVED from what {@link WorldViewJson} emits rather than hand-listed, so a new
 * key cannot ship undocumented. The keys are required QUOTED ('damage', not damage) because these
 * are short common words: a bare {@code contains("damage")} passes on the fall-damage paragraph
 * elsewhere in this 3KB string, and {@code contains("side")} passes on the word "outside". That
 * hollow shape has been caught in this repo before, matching "24" against an unrelated "-4 OPEN
 * trapdoor".
 *
 * <p>The second half pins the ENCODING the legend describes, since a legend is only true while its
 * encoding holds: if {@code maxDamage} started being emitted unconditionally, the discriminator
 * rule above would silently become a lie with every assertion still green.
 */
public class InventoryAndTargetSemanticsAreDocumentedTest {

    /** Vanilla's own numbers, so the worked example cannot drift from the game. */
    private static final int DIAMOND_MAX_USES = Item.ToolMaterial.EMERALD.getMaxUses();
    private static final int WORN_DAMAGE = 1520;

    private static String description(String toolName) {
        ToolRegistry reg = new ToolRegistry(new ToolContext(null, null, null, null, null));
        for (SyncToolSpecification spec : reg.all()) {
            if (spec.tool().name().equals(toolName)) {
                return spec.tool().description();
            }
        }
        throw new AssertionError("tool not found: " + toolName);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> slotMap(InventoryView.Slot s) {
        Map<String, Object> inv = WorldViewJson.invMap(new InventoryView(0, List.of(s)));
        return asMap(((List<?>) inv.get("slots")).get(0));
    }

    /** A damageable stack: the branch where {@code damage} means wear. */
    private static InventoryView.Slot wornPickaxe() {
        return new InventoryView.Slot(0, "diamond_pickaxe", 1, WORN_DAMAGE, DIAMOND_MAX_USES);
    }

    /** A non-damageable stack: the branch where the SAME key means variant metadata. */
    private static InventoryView.Slot stoneSlabVariant() {
        return new InventoryView.Slot(3, "stone_slab", 64, 3, null);
    }

    /** Every key the inventory section can emit, across both branches. */
    private static Set<String> everyInventoryKey() {
        Set<String> keys = new LinkedHashSet<>(
                WorldViewJson.invMap(new InventoryView(0, List.of())).keySet());
        keys.remove("slots");                       // container; its ROWS are what needs a legend
        keys.addAll(slotMap(wornPickaxe()).keySet());
        keys.addAll(slotMap(stoneSlabVariant()).keySet());
        return keys;
    }

    /** Every key the target section can emit -- it differs per hitType, so all three branches. */
    private static Set<String> everyTargetKey() {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(WorldViewJson.targetMap(blockHit(2.4)).keySet());
        keys.addAll(WorldViewJson.targetMap(entityHit(3.1)).keySet());
        keys.addAll(WorldViewJson.targetMap(TargetView.miss()).keySet());
        return keys;
    }

    private static TargetView blockHit(double eyeToHitVec) {
        return new TargetView("block", "stone", 12, 64, -7, "UP", null, null, null, eyeToHitVec);
    }

    private static TargetView entityHit(double feetToFeet) {
        return new TargetView("entity", null, null, null, null, null, 77, "Zombie", 14, feetToFeet);
    }

    @Test
    public void everyInventoryAndTargetKeyIsNamedInTheWorldViewDescription() {
        String desc = description("world_view");
        Set<String> emitted = new LinkedHashSet<>(everyInventoryKey());
        emitted.addAll(everyTargetKey());
        for (String key : emitted) {
            assertTrue("world_view emits '" + key + "' but its description never names it as a "
                            + "quoted key, so the model has no legend for it",
                    desc.contains("'" + key + "'"));
        }
    }

    @Test
    public void theDamageDirectionIsStatedAndTheWorkedExampleMatchesVanilla() {
        String desc = description("world_view");
        assertTrue("the DIRECTION must be stated: 'damage' counts wear upward, which is the "
                        + "opposite of the durability bar the model has seen in-game",
                desc.contains("counts WEAR UPWARD FROM 0"));
        assertTrue("and the inverse reading must be denied outright, since it is the natural one",
                desc.contains("NOT durability remaining"));
        assertTrue("the arithmetic for what the model actually wants must be given",
                desc.contains("'maxDamage'-'damage'"));

        // Vanilla's tooltip prints (getMaxDamage() - getItemDamage()) at ItemStack:845, so the
        // worked example is derivable and must agree with the material's own max uses.
        int remaining = DIAMOND_MAX_USES - WORN_DAMAGE;
        assertTrue("the worked example must use vanilla's max uses (" + DIAMOND_MAX_USES + ")",
                desc.contains("\"maxDamage\":" + DIAMOND_MAX_USES));
        assertTrue("and spell out the consequence in uses left (" + remaining + "), because that "
                        + "is the number the repair decision turns on",
                desc.contains(remaining + " left"));
    }

    @Test
    public void theTwoMeaningsOfDamageAreSeparatedByAStatedRule() {
        String desc = description("world_view");
        assertTrue("the field means wear or METADATA depending on the item, and the description "
                + "must say so", desc.contains("METADATA"));
        // Naming both meanings is not enough: the model needs the TEST for telling them apart,
        // and the only thing on the wire that distinguishes them is maxDamage's presence.
        assertTrue("'maxDamage' must be named as the discriminator between the two meanings",
                desc.contains("'maxDamage' is the discriminator"));
        assertTrue("the wear branch must be keyed on maxDamage being PRESENT",
                desc.contains("WITH 'maxDamage'"));
        assertTrue("the metadata branch must be keyed on maxDamage being ABSENT",
                desc.contains("WITHOUT 'maxDamage'"));
        assertFalse("an absent maxDamage must not be readable as \"undamaged\": an Unbreakable "
                        + "tool omits it too (isItemStackDamageable checks that tag)",
                desc.contains("absent 'maxDamage' means undamaged"));
    }

    @Test
    public void eachDistanceOriginIsDocumentedPerHitType() {
        String desc = description("world_view");
        assertTrue("the description must say the origin depends on hitType, or the model will "
                        + "compare a block distance against an entity one",
                desc.contains("ORIGIN DEPENDS ON 'hitType'"));
        assertTrue("block: eye to the hit point on the face (mop.hitVec vs posY+getEyeHeight)",
                desc.contains("EYE to the exact hit point"));
        assertTrue("entity: getDistanceToEntity, which is posY to posY -- the bounding-box "
                        + "bottoms, not the eyes", desc.contains("FEET TO FEET"));
    }

    @Test
    public void theBlockReachBoundIsStatedOnVanillasNumbers() {
        String desc = description("world_view");
        // PlayerControllerMP.getBlockReachDistance(), which is what EntityRenderer.getMouseOver
        // passes to rayTrace -- so the raytrace cannot return a block beyond it. "Is it in reach"
        // is answered by the hit EXISTING, not by comparing the number to a threshold.
        assertTrue("survival reach must be stated, since the model reads distance to decide "
                + "whether it may dig", desc.contains("4.5 blocks in survival"));
        assertTrue("creative differs and the model may be in either mode",
                desc.contains("5.0 in creative"));
        assertTrue("and the conclusion must be drawn: a block hit is already within reach",
                desc.contains("already in reach"));
    }

    @Test
    public void aMissIsDocumentedAsNoTargetRatherThanZeroRange() {
        String desc = description("world_view");
        assertEquals("a miss must carry the 0.0 the legend describes", 0.0,
                TargetView.miss().distance(), 0.0);
        assertTrue("0.0 on a miss is not a distance, and saying so is the whole point",
                desc.contains("NO TARGET AT ALL"));
        assertTrue("the misreading must be named, not merely avoided",
                desc.contains("not a target at zero range"));
        assertTrue("and the ordering rule the model must follow",
                desc.contains("read 'hitType' before you read 'distance'"));
    }

    // ---- the encoding the legend above describes, so the legend cannot rot silently ----

    @Test
    public void maxDamageIsOmittedForANonDamageableStackAndDamageNeverIs() {
        Map<String, Object> slab = slotMap(stoneSlabVariant());
        assertFalse("'maxDamage' must stay OFF the wire when the stack cannot wear, or its "
                        + "presence stops being the discriminator the description promises",
                slab.containsKey("maxDamage"));
        assertEquals("and 'damage' must still be emitted, carrying the variant metadata",
                3, slab.get("damage"));

        Map<String, Object> pick = slotMap(wornPickaxe());
        assertEquals("a damageable stack must carry vanilla's max uses so remaining is derivable",
                DIAMOND_MAX_USES, pick.get("maxDamage"));
        assertEquals("and the raw wear, undoctored: the projection must not helpfully invert it "
                        + "into remaining, which would make every legend above wrong",
                WORN_DAMAGE, pick.get("damage"));
    }

    @Test
    public void aMissCarriesNeitherBlockNorEntityKeysButStillCarriesDistance() {
        Map<String, Object> miss = WorldViewJson.targetMap(TargetView.miss());
        assertEquals("miss", miss.get("hitType"));
        for (String key : List.of("block", "pos", "side", "entityId", "entityType", "entityHp")) {
            assertFalse("a miss must not emit '" + key + "': the description tells the model that "
                    + "hitType alone decides which keys exist", miss.containsKey(key));
        }
        assertEquals("'distance' is emitted unconditionally, which is exactly why the 0.0 needs "
                + "documenting rather than the key being dropped", 0.0, miss.get("distance"));
    }

    @Test
    public void blockAndEntityHitsEmitDisjointKeySetsAsTheLegendClaims() {
        Map<String, Object> block = WorldViewJson.targetMap(blockHit(2.4));
        Map<String, Object> entity = WorldViewJson.targetMap(entityHit(3.1));
        assertTrue("a block hit must carry absolute 'pos', not grid-relative offsets",
                List.of(12, 64, -7).equals(block.get("pos")));
        assertEquals("UP", block.get("side"));
        assertFalse("a block hit must not carry entity keys", block.containsKey("entityId"));
        assertFalse("an entity hit must not carry 'pos', which the model would read as a "
                + "block coordinate", entity.containsKey("pos"));
        assertEquals("entityHp is emitted rounded, per the description", 14, entity.get("entityHp"));
        assertFalse("a non-living entity has no hp and must omit the key rather than send 0",
                WorldViewJson.targetMap(new TargetView("entity", null, null, null, null, null,
                        9, "Item", null, 1.0)).containsKey("entityHp"));
    }
}
