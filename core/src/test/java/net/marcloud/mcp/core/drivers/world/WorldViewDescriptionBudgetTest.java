package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.io.transport.ToolRegistry;
import org.junit.Test;

/**
 * The per-turn description budget for {@code world_view}, and the capability the budget must not
 * be bought with.
 *
 * <p><b>Why a budget test at all.</b> The description was 10,233 characters, re-sent to the model
 * on EVERY turn of a long-horizon session — 31.6% of this registry's per-turn description tax —
 * for a tool many turns never touch. Nothing in the build stops prose from growing back, because
 * prose costs nothing to compile. This pins the size and, in the same file, pins that the text
 * was DEFERRED rather than deleted.
 *
 * <p><b>Why the second half matters more than the first.</b> A budget test alone is satisfied by
 * deleting the documentation, which is the worse outcome: a model reading {@code "walk":-2} with
 * no legend walks into lava. So the assertions below split the same material two ways.
 *
 * <ul>
 *   <li><b>Always-on</b> ({@link WorldViewLegend#HEADER}): every rule whose NAIVE reading changes
 *       what the agent DOES. Removing one is a capability loss no fetch repairs.</li>
 *   <li><b>On demand</b> ({@code world_view{explain:...}}): derivations, worked examples and the
 *       long encodings — expensive per turn, free per fetch.</li>
 * </ul>
 *
 * <p>The vocabulary in these assertions is DERIVED from what the payload actually emits
 * ({@link WorldViewJson}) rather than hand-listed, so a key that ships without a legend fails
 * here rather than in front of a model.
 */
public class WorldViewDescriptionBudgetTest {

    /**
     * The per-turn ceiling for every description this registry publishes.
     *
     * <p>Deliberately generous over the measured post-split total and far under the measured
     * pre-split 32,407: a little genuinely new documentation still fits, and anything approaching
     * the old size does not.
     */
    private static final int REGISTRY_DESC_BUDGET = 28_000;

    /** What the tool shipped as one blob, before the split. The number being improved on. */
    private static final int BEFORE_THE_SPLIT = 10_233;

    /**
     * The per-turn ceiling for {@code world_view} alone.
     *
     * <p><b>RAISED TWICE, and the second raise is the lesson.</b> The first pass set 5,000 against
     * a 3,812 header and got it green by deferring facts the specification tests pin. Restoring
     * those by TEST NAME put it at 5,103, and I raised it to 5,600. But test names are not the
     * rule: re-deriving the deferral set from the actual rule — "a line is always-on when the
     * naive reading of the value produces a DIFFERENT ACTION" — moved seven more items inline that
     * no test had caught, reaching 5,985. Each is one I can name a different action for: a caller
     * that never fetches would omit {@code sections} and pay full price, read a failed inventory
     * read as an empty kit, believe a threat is gone, place on the wrong face, treat an unmeasured
     * shaft as lethal, build at the wrong Y, or read an unreadable air reading as fine.
     *
     * <p>So the constant is now set from the measured honest size plus headroom, and this Javadoc
     * says what governs it. It is a ratchet against regrowth, not an optimisation target: the
     * number to argue with is the rule above, and this test's job is to catch a regression rather
     * than to push the header down.
     */
    private static final int WORLD_VIEW_BUDGET = 6_400;

    /**
     * The size guard, written as a FAILURE MESSAGE that says what to do rather than only that a
     * number moved.
     *
     * <p>A bare {@code assertTrue(len <= N)} tells whoever trips it only that a number changed. The
     * actionable form names the two legitimate ways past it — cut explanatory prose, or move it
     * behind {@code explain} — because those are the only two moves that keep the capability.
     */
    @Test
    public void worldViewDescriptionStaysUnderItsPerTurnBudget() {
        String desc = worldViewDescription();
        assertTrue("world_view's description is " + desc.length() + " chars, over its "
                + WORLD_VIEW_BUDGET + " per-turn budget. It was " + BEFORE_THE_SPLIT + " before "
                + "the split, so this is the metric regressing rather than a first draft. Either "
                + "cut prose that explains rather than instructs, or move it behind "
                + "world_view{explain:...} — but do NOT delete a rule whose naive reading changes "
                + "what the agent does, and do not delete a key legend to make a number pass.",
                desc.length() <= WORLD_VIEW_BUDGET);
    }

    /**
     * The registry-wide bill, which is what a turn actually pays.
     *
     * <p>Asserted on DESCRIPTIONS rather than descriptions-plus-schema: the schema is structured,
     * and a client that understands it gets the enum and the types for a fraction of what the
     * prose costs.
     */
    @Test
    public void theWholeRegistrysDescriptionBillStaysUnderItsCeiling() {
        int total = 0;
        for (SyncToolSpecification spec : new ToolRegistry(null).all()) {
            total += spec.tool().description().length();
        }
        assertTrue("all 25 tool descriptions now total " + total + " chars, over the "
                + REGISTRY_DESC_BUDGET + " per-turn ceiling (32,407 before the world_view split). "
                + "world_view is not the only tool whose description can grow: find the one that "
                + "grew and apply the same rule — keep what changes an action, defer what only "
                + "explains one.",
                total <= REGISTRY_DESC_BUDGET);
    }

    /**
     * The saving has to be REAL, and the only way to know that is to check the deferred text still
     * exists.
     *
     * <p>This is what makes the budget honest. If someone later satisfies
     * {@link #worldViewDescriptionStaysUnderItsPerTurnBudget()} by deleting the documentation, the
     * size assertion still passes and the agent silently loses the ability to read its own
     * payloads. Pinning {@code HEADER + deferred} against the old size means the material is
     * accounted for: it shrank, but it did not evaporate, and each deferred section has to earn
     * its place by carrying material the header does not.
     */
    @Test
    public void theSplitMovedDocumentationRatherThanDeletingIt() {
        int reachable = WorldViewLegend.HEADER.length() + WorldViewLegend.deferredChars();
        assertTrue("header (" + WorldViewLegend.HEADER.length() + ") plus deferred ("
                + WorldViewLegend.deferredChars() + ") is " + reachable + " chars, at or above the "
                + BEFORE_THE_SPLIT + " the tool shipped as one blob — so the split saved nothing an "
                + "agent can notice, and each deferred section must instead earn its place by "
                + "carrying material the header does not already state.",
                reachable < BEFORE_THE_SPLIT);
    }

    // NOTE: a "no section restates the header" guard was written here and REMOVED. Two versions
    // failed, and both failures are the finding worth keeping:
    //  - whole-sentence matching cannot see a lightly-edited paste, so mutation M15 survived it;
    //  - longest-shared-word-run measured clean sections at runs of 2-5 and the M15 paste at 5,
    //    so no threshold separates them and the check failed on legitimate text.
    // A metric that cannot tell a paste from a section about the same subject is not a guard, so
    // the duplication this was meant to catch is UNCOVERED rather than falsely pinned. The
    // budget itself is still covered by the two size tests above.

    // ---- the capability the budget must not be bought with -------------------------------

    /**
     * Every key the grid actually emits must be documented SOMEWHERE the agent can reach.
     *
     * <p>Not "in the header": the whole point of the split is that a rarely-needed key legend may
     * live behind a fetch. The property that matters is that no key's meaning exists only in a
     * Java comment — the defect this repo wrote siblings of this test after {@code world_view}
     * shipped emitting {@code walk} and {@code drop} with no legend at all.
     */
    @Test
    public void everyEmittedGridKeyIsDocumentedSomewhereReachable() {
        String reachable = allReachableText();
        for (String key : everyGridKeyEmitted().keySet()) {
            assertTrue("world_view emits grid key '" + key + "' but no text the agent can reach "
                    + "names it, so the model has no legend for it", reachable.contains(key));
        }
    }

    /** Same, for the inventory and target sections. */
    @Test
    public void everyEmittedInventoryAndTargetKeyIsDocumentedSomewhereReachable() {
        String reachable = allReachableText();
        for (String key : everyInventoryAndTargetKeyEmitted()) {
            assertTrue("world_view emits '" + key + "' but no reachable text names it as a quoted "
                    + "key, so the model has no legend for it", reachable.contains("'" + key + "'"));
        }
    }

    /**
     * The rules whose NAIVE reading produces a DIFFERENT ACTION must be ALWAYS-ON.
     *
     * <p>These are the reason the header is 3,840 chars rather than 1,500, and this test is why
     * that is defensible rather than stubborn. Each, misread, costs the agent something physical:
     *
     * <ul>
     *   <li>{@code damage} read as durability remaining discards a pickaxe with 41 uses left;</li>
     *   <li>a {@code miss} distance of 0.0 read as "in range" swings at a wall;</li>
     *   <li>{@code walk} ABSENT read as unknown (or {@code "?"} read as walkable) is a death;</li>
     *   <li>{@code entities.left} read as "gone" is a creeper standing behind you.</li>
     * </ul>
     *
     * <p>Asserted on the HEADER and not on {@code allReachableText()}: deferring one of these is
     * a capability loss no fetch repairs, because the agent has already acted by the time it
     * thinks to ask.
     */
    @Test
    public void theActionChangingDecodingRulesStayInTheAlwaysOnHeader() {
        String h = WorldViewLegend.HEADER;

        // damage counts UP toward maxDamage; remaining is the subtraction.
        assertTrue("the header must state that 'damage' counts WEAR UPWARD, or a pickaxe at "
                + "damage 1520/1561 is discarded with 41 uses left",
                h.contains("counts WEAR UPWARD FROM 0"));
        assertTrue("and must give the inverse, since that is the arithmetic the agent wants",
                h.contains("'maxDamage'-'damage'"));
        assertTrue("and must say an absent maxDamage is NOT undamaged — Unbreakable omits it too",
                h.contains("Never read an absent"));

        // a miss is not a target at zero range.
        assertTrue("the header must say a miss means NO TARGET AT ALL", h.contains("NO TARGET AT ALL"));
        assertTrue("and must deny the natural misreading",
                h.contains("not a target at zero range"));
        assertTrue("and must give the ordering rule that stops the agent reading distance first",
                h.contains("read 'hitType' before you read 'distance'"));
        assertTrue("the header must say an ABSENT 'walk' means WALKABLE — inverted, it refuses to "
                + "walk on every clear tile", h.contains("ABSENT means WALKABLE"));
        assertTrue("and must say \"?\" is NOT walkable, since that is the distinction the encoding "
                + "exists to preserve", h.contains("NOT walkable"));
        assertTrue("and must say an ABSENT 'drop' means 0", h.contains("ABSENT means 0"));
        assertTrue("and must give the fall-damage scale, because the number is HP and a model "
                + "assuming hearts doubles every consequence",
                h.contains("max(0, drop-3) HP") && h.contains("20 HP = 10 hearts"));

        // entities.left is about sampling.
        assertTrue("the header must say 'entities.left' does NOT mean the entity is gone",
                h.contains("does NOT mean the"));
        assertTrue("and must name the honest alternative, which is SAMPLING", h.contains("SAMPLING"));

        // the absence-means-unchanged rule every diff read depends on.
        assertTrue("the header must state that an absent diff key means UNCHANGED",
                h.contains("UNCHANGED"));
        assertTrue("and must name the marker a caller branches on", h.contains("'unsampled':true"));
        assertTrue("and must say blockCounts is SURFACE-only, or absence reads as 'not present' "
                + "and the agent stops looking for ore that is there",
                h.contains("SURFACE block only"));
    }

    /**
     * THE GOVERNING RULE, stated where the next editor will find it.
     *
     * <p>A line is ALWAYS-ON when the naive reading of the value produces a DIFFERENT ACTION. A
     * line is DEFERRED when it only explains, quantifies or derives a value the agent is already
     * looking at.
     *
     * <p><b>Read this before moving anything out of the header.</b> Three passes got this wrong
     * in the same way, and the failure mode is worth naming because it is structural, not a slip:
     *
     * <ol>
     *   <li>Pass one deferred eight facts and reported DONE with a green suite — the suite being
     *       the one I had written.</li>
     *   <li>Pass two restored exactly those eight, because they were the ones that went RED. The
     *       protected set had silently become "whatever a test happens to assert", so any fact
     *       no test asserted was deferrable — which is most of them.</li>
     *   <li>Pass three re-derived the set from the rule above and found seven more that no test
     *       covered, including the {@code sections} vocabulary itself.</li>
     * </ol>
     *
     * <p>So do not use the failing tests as the checklist. Ask the question above of every
     * deferred item and write down the different action; if you cannot, the item is not
     * deferrable and belongs inline. The tests are confirmation, not the safety net.
     */
    @Test
    public void theHeaderNamesTheFullSectionsVocabularyNotJustThatSectionsExists() {
        // The one that got through twice: the schema lists six names, and a caller reading only
        // the description used to learn that `sections` EXISTS but not what to put in it.
        assertTrue("world_view must name the sections vocabulary inline. A model that knows "
                + "'sections' exists but not the six legal names either omits it and pays full "
                + "price every poll, or guesses and eats a refusal: " + WorldViewLegend.HEADER,
                WorldViewLegend.HEADER.contains(
                        String.join(",", WorldViewCapture.SECTIONS)));
    }

    /**
     * The header must not restate what the OTHER tool already says.
     *
     * <p>{@code scan_surroundings} and {@code capture_screen} each already point the model at
     * {@code world_view}; a third copy inside the tool being pointed AT is pure per-turn tax. This
     * one is a CUT rather than a move, so unlike the rest there is nothing left to fetch — which
     * is why it gets its own assertion instead of riding along above.
     */
    @Test
    public void theHeaderDoesNotRepeatWhatTheOtherToolsAlreadySay() {
        assertTrue("world_view must not tell the model to prefer it over capture_screen: "
                + "capture_screen's own description already says it is the secondary channel, and "
                + "a third copy is per-turn tax for a sentence the agent has already read twice",
                !WorldViewLegend.HEADER.contains("capture_screen"));
    }

    /**
     * {@code explain} must be a closed enum, not free text.
     *
     * <p>Same reason {@code profile} and {@code mode} are: a sentence listing legal values can be
     * misread where an enum cannot, and this tool already learned that — a live client was told
     * {@code unknown status 'start'} by a tool whose schema said {@code type: string} and listed
     * the values in prose.
     */
    @Test
    public void explainIsAClosedEnumDerivedFromTheLegendItFetches() {
        Object props = ((Map<?, ?>) worldViewTool().inputSchema()).get("properties");
        Object explain = ((Map<?, ?>) props).get("explain");
        assertNotNull("world_view must publish 'explain', or the header points at a fetch nothing "
                + "implements and the deferred legend is unreachable", explain);

        Object enumValues = ((Map<?, ?>) explain).get("enum");
        assertNotNull("'explain' must be a closed enum so a typo is rejected naming the path, "
                + "not silently treated as a world sample", enumValues);
        assertEquals("the schema enum and the legend's own section list are one source of truth, "
                        + "so a section added to one cannot be missing from the other",
                List.copyOf(WorldViewLegend.sectionNames()), List.copyOf((List<?>) enumValues));
    }

    /**
     * Every section the tool promises to fetch must exist as a fetchable section.
     *
     * <p>Written after mutation M10: deleting the {@code grid} section outright — from the legend,
     * from the enum, and from the header's own list of fetchable names — left every other test in
     * this file green. That is the exact shape of a capability loss a budget test waves through:
     * nothing got bigger, the arithmetic still improved, and the agent quietly lost the ability to
     * fetch a chunk of the documentation.
     *
     * <p>What closes it is an INVENTORY rather than another size check. The header is the
     * promise and the enum is the delivery, so both are pinned to the same explicit list and a
     * section removed from one side no longer passes.
     */
    @Test
    public void everyFetchableSectionTheHeaderPromisesStillResolves() {
        assertEquals("the section inventory IS the contract between the header's promise and the "
                        + "fetch's delivery — dropping one from both sides is a silent capability "
                        + "loss, not a saving. 'env' is here because the environment section is the "
                        + "only one that used to ship with NO legend at all, and an unlegended "
                        + "section is invisible: the model cannot look for a key it was never told "
                        + "exists",
                List.of("grid", "entities", "inventory", "target", "env", "diff"),
                WorldViewLegend.sectionNames());
        for (String section : WorldViewLegend.sectionNames()) {
            assertNotNull("explain='" + section + "' is advertised, so it must resolve to text",
                    WorldViewLegend.explain(section));
            assertTrue("and the header must name it, or a section exists the agent is never told "
                    + "it can fetch", WorldViewLegend.HEADER.contains("'" + section + "'"));
        }
    }

    // ---- the fetch actually works -----------------------------------------------------------

    /**
     * The fetch returns the legend, verbatim, for every section.
     *
     * <p>Verbatim rather than "contains something": the fetch and the documentation drifting apart
     * is the failure mode that would make this whole split a lie, and a substring check would not
     * notice it.
     */
    @Test
    public void explainReturnsTheSectionLegendWithoutSampling() {
        for (String section : WorldViewLegend.sectionNames()) {
            assertEquals("explain='" + section + "' must return that section's legend verbatim, "
                    + "or the on-demand path and the documentation have drifted",
                    WorldViewLegend.explain(section), call(Map.of("explain", section)));
        }
    }

    /**
     * An unknown section is REFUSED, naming the legal ones.
     *
     * <p>The same rule {@code sections} already follows, for the same reason: a name that matched
     * nothing used to produce a perfectly successful reply whose only defect was the absence, and
     * a caller cannot tell that from a real answer.
     */
    @Test
    public void anUnknownExplainSectionIsRefusedRatherThanIgnored() {
        String text = call(Map.of("explain", "gridz"));
        assertTrue("an unrecognised explain section must be refused naming the legal ones, "
                + "because a silently-ignored argument reads as a world sample that happened to "
                + "return no legend: " + text, text.contains("unknown explain section"));
        assertTrue("and the refusal must name what IS legal: " + text,
                text.contains(String.join(", ", WorldViewLegend.sectionNames())));
    }
    /**
     * {@code explain} must be answered BEFORE any capture.
     *
     * <p>Two things break if the fetch reaches the capture. It costs a world scan to read prose,
     * and it writes {@code lastWorldView} — silently rebasing every subsequent diff onto a view
     * the agent never received, so "nothing changed" becomes true against the wrong previous
     * state, which is the one failure diff mode exists to prevent.
     *
     * <p>Observed through the REPLY, not through the source. Headless there is no world, so a
     * fetch that fell through to the capture could only answer
     * {@code world_view failed: GameBridge not initialized} — never the legend. The two orderings
     * are therefore distinguishable by what the caller gets back, and the assertion fails for the
     * real reason instead of because a sentence was reworded.
     *
     * <p>The baseline half is deliberately not asserted here: it is not observable without a live
     * game, since the capture throws before {@code lastWorldView} is written, so a headless run
     * cannot tell a rebased baseline from an unwritten one. The ordering asserted above is what
     * rules it out — there is no path from the fetch to that write.
     */
    @Test
    public void explainIsAnsweredBeforeTheSamplerSoAProseFetchNeverCostsAWorldScan() {
        ToolRegistry reg = new ToolRegistry(null);
        SyncToolSpecification spec = null;
        for (SyncToolSpecification s : reg.all()) {
            if (s.tool().name().equals("world_view")) {
                spec = s;
            }
        }
        assertNotNull("world_view missing from the registry", spec);

        String text = invoke(spec, Map.of("explain", "diff"));
        assertEquals("the fetch must be answered with the legend; anything else means it reached "
                + "the sampler, which costs a world scan to read prose and fails when not "
                + "in-world", WorldViewLegend.explain("diff"), text);
        assertTrue("a fetch that fell through to the capture would report a GameBridge failure or "
                + "the world view's own shape, never prose: " + text,
                !text.contains("GameBridge") && !text.contains("\"present\""));
    }

    /** Invoke a specific spec's handler and return its text content. */
    private static String invoke(SyncToolSpecification spec, Map<String, Object> args) {
        CallToolResult r = spec.callHandler().apply(null, new CallToolRequest("world_view", args));
        StringBuilder sb = new StringBuilder();
        for (Content c : r.content()) {
            if (c instanceof TextContent t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
    /**
     * The header must name the fetch that the schema actually offers.
     *
     * <p>The header is the only thing the agent reads before its first {@code explain} call, so a
     * header naming a section the fetch rejects is a dead end the agent walks into at the exact
     * moment it is confused.
     */
    @Test
    public void everySectionTheHeaderAdvertisesIsOneTheFetchAccepts() {
        for (String section : WorldViewLegend.sectionNames()) {
            assertTrue("the header advertises explain:'" + section + "' so the agent must be able "
                    + "to fetch it", WorldViewLegend.HEADER.contains("'" + section + "'"));
            assertNotNull("explain='" + section + "' is advertised and must resolve to text",
                    WorldViewLegend.explain(section));
        }
    }

    // ===== the encoding the legend describes, so the legend cannot rot silently =============

    /**
     * A miss returns the documented sentinel, so the header's claim about it has an anchor.
     *
     * <p>A legend is only true while the encoding it documents holds: if {@code miss()} started
     * carrying a real distance, every word the header spends on it would be a lie that still
     * passes the size budget.
     */
    @Test
    public void aMissStillCarriesTheSentinelDistanceTheHeaderDescribes() {
        assertEquals("the header promises 0.0 on a miss means NO TARGET AT ALL; if the encoding "
                + "changes, the legend is a lie", 0.0, TargetView.miss().distance(), 0.0);
        assertNull("and a miss carries neither block nor entity keys, which is what lets a "
                + "caller branch on hitType alone",
                WorldViewJson.targetMap(TargetView.miss()).get("entityId"));
    }

    // ===== helpers ==========================================================================

    /** Everything the agent can reach: the always-on header plus every deferred section. */
    private static String allReachableText() {
        StringBuilder sb = new StringBuilder(WorldViewLegend.HEADER);
        for (String s : WorldViewLegend.sectionNames()) {
            sb.append('\n').append(WorldViewLegend.explain(s));
        }
        return sb.toString();
    }

    private static Tool worldViewTool() {
        for (SyncToolSpecification spec : new ToolRegistry(null).all()) {
            if (spec.tool().name().equals("world_view")) {
                return spec.tool();
            }
        }
        throw new AssertionError("world_view is missing from the registry");
    }

    private static String worldViewDescription() {
        return worldViewTool().description();
    }

    /** Call the registered handler and return its text content. */
    private static String call(Map<String, Object> args) {
        for (SyncToolSpecification spec : new ToolRegistry(null).all()) {
            if (spec.tool().name().equals("world_view")) {
                CallToolResult r = spec.callHandler()
                        .apply(null, new CallToolRequest("world_view", args));
                StringBuilder sb = new StringBuilder();
                for (Content c : r.content()) {
                    if (c instanceof TextContent t) {
                        sb.append(t.text());
                    }
                }
                return sb.toString();
            }
        }
        throw new AssertionError("world_view is missing from the registry");
    }

    /**
     * A grid column exercising every emitting branch of {@link WorldViewJson#gridMap}: a real
     * drop, an unknown walk, and a run-length profile.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> everyGridKeyEmitted() {
        LocalGrid.Column col = new LocalGrid.Column(1, 0, -2, "stone", "air", "?",
                List.of(new LocalGrid.Run("stone", 0, 3)), 7, LocalGrid.WALK_UNKNOWN);
        LocalGrid g = new LocalGrid(1, "surface", 0, 64, 0, List.of(col), Map.of());
        Object columns = WorldViewJson.gridMap(g).get("columns");
        return (Map<String, Object>) ((List<?>) columns).get(0);
    }

    /** Every key {@link WorldViewJson} can emit for the inventory and target sections. */
    private static List<String> everyInventoryAndTargetKeyEmitted() {
        List<String> keys = new ArrayList<>(WorldViewJson.invMap(new InventoryView(
                0, List.of(new InventoryView.Slot(0, "diamond_pickaxe", 1, 5, 1561)))).keySet());
        keys.addAll(WorldViewJson.targetMap(TargetView.miss()).keySet());
        keys.addAll(WorldViewJson.targetMap(new TargetView(
                "block", "stone", 1, 2, 3, "north", null, null, null, 3.5)).keySet());
        keys.addAll(WorldViewJson.targetMap(new TargetView(
                "entity", null, null, null, null, null, 42, "Creeper", 20, 2.5)).keySet());
        return keys;
    }
}