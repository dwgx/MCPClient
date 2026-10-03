package net.marcloud.mcp.core.drivers.world;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code world_view} tool's documentation, split into an always-on {@link #HEADER} and the
 * on-demand sections reachable through {@code world_view{explain:...}} -- one per payload
 * section, plus the diff encoding. The inventory of those names is {@link #sectionNames()}, and
 * {@link #SECTIONS_TEXT} is the single copy of it.
 *
 * <p><b>Why this exists.</b> {@code world_view} shipped a single 10,233-character description
 * re-sent to the model on every turn — 31.6% of this registry's per-turn description tax, paid
 * whether or not the turn touched the world. Almost none of it is "what the tool does"; it is a
 * field legend, and a legend is only needed once there is a payload to decode.
 *
 * <p><b>The split rule, and it is not "short vs long".</b> A line is always-on when the naive
 * reading of the value produces a DIFFERENT ACTION — {@code walk:-2} read as walkable walks you
 * into lava, {@code damage:1520} read as durability discards a pickaxe with 41 uses left. A line
 * is deferred when it only explains, quantifies, or derives a value the agent is already looking
 * at: the raytrace's reach bound, the worked pickaxe example, the run-length encoding of a column.
 * Deferring the first kind would be a capability loss disguised as a saving, so those stayed.
 *
 * <p><b>The two halves do not overlap.</b> Each section is exactly what its header paragraph left
 * out, which is what makes the saving honest: {@code HEADER.length() + deferredChars()} is the
 * total text still reachable by the agent, and the per-turn cost is {@code HEADER} alone.
 *
 * <p><b>What was deleted rather than moved.</b> Changelog prose ("previously every id you knew
 * reported left at once"), derivations the model can perform itself ("drop=13 exactly half your
 * health" is {@code 13-3=10} of a stated 20 HP), and two cross-tool comparisons already made in
 * the OTHER tool's description — {@code scan_surroundings} and {@code capture_screen} each already
 * point at {@code world_view}, so repeating it here was a third copy of the same sentence.
 */
public final class WorldViewLegend {

    private WorldViewLegend() {
    }

    /**
     * The always-on description: what the tool returns, how to shape the call, and every decoding
     * rule whose NAIVE reading changes what the agent DOES. What a value <i>means</i> rather than
     * <i>how to act on it</i> is one {@code explain} call away.
     */
    public static final String HEADER =
            "[requires: in-world] Structured world snapshot: self (pos/vel/look/hp/food/xp/"
            + "xpProgress/armor/air/effects/gamemode/flags), a columnar grid of nearby blocks, "
            + "nearby entities (sorted, capped), per-slot inventory (registry names), the "
            + "crosshair target (raytrace) and env ('dimension'/'biome'/'timeOfDay'/'worldTime'/"
            + "'raining'/'thundering'/'daytime'/'lightAtPlayer'). "
            + "'profile'=sparse|explore|combat trades breadth for cost, 'mode'=diff returns only "
            + "what changed since your last call, 'radius' overrides grid size, and 'sections' "
            + "takes a subset of " + String.join(",", WorldViewCapture.SECTIONS) + " -- the only "
            + "six names it accepts, any other is REFUSED with an error naming it rather than "
            + "silently ignored, because a view missing the section you asked for looks exactly "
            + "like one where that section did not change. "
            + "DECODING LEGEND: what the values mean, and how not to misread them. "
            + "GRID. A column carries dx,dz (offset from you), surface with surfaceDy (height "
            + "relative to your feet), feet and head (blocks at your own two body levels), and two "
            + "TERRAIN-SAFETY keys OMITTED in their common case, so their absence is meaningful. "
            + "'walk' is vanilla's passability verdict for the square you would stand in: ABSENT "
            + "means WALKABLE, and the values are 0 blocked (solid or a door), 2 "
            + "clear-but-in-water-or-on-a-closed-trapdoor, -2 lava, -3 fence/wall/closed-gate/rail, "
            + "-4 OPEN trapdoor, \"?\" unobtainable. \"?\" is NOT walkable, it means unknown. It "
            + "judges the 1x2 volume at your own feet height, so an ordinary 1-block step-up reads "
            + "0 even though you could step onto it. 'drop' is free-fall blocks below that square: "
            + "ABSENT means 0, a number is the depth, and \"deep\" means the probe hit its 24-block "
            + "bound, i.e. certainly lethal. Fall damage is max(0, drop-3) HP on the same 20-HP "
            + "scale as your own 'hp' field (20 HP = 10 hearts, one heart 2 HP), so drop<=3 harmless, "
            + "drop=13 exactly half your health and drop>=23 fatal at full health; treat "
            + "the number as a LOWER bound, it is whole-block arithmetic. Any of "
            + "surface/feet/head can be \"?\", which is NOT a block named ? but a cell that could "
            + "not be READ (unloaded chunk or failed lookup) -- unknown terrain to dig or stand on, "
            + "it is excluded from 'blockCounts' rather than tallied as a block type, and its "
            + "'drop' there is UNMEASURED rather than a measured shaft, so \"deep\" on an unread "
            + "column does not mean lethal. An absent column 'profile' means the profile chose not "
            + "to emit it, not that the column has no height. "
            + "'blockCounts' counts each column's SURFACE block only, NOT the volume, so anything "
            + "buried or under a ceiling is absent from it; never conclude a block type is missing "
            + "from its absence there, ask find_block. "
            + "INVENTORY. 'slots' lists only the NON-EMPTY slots of the 36-slot main inventory "
            + "(0-8 is the hotbar, 'selectedSlot' is the one you hold; armour is not included), "
            + "each with 'index', 'item', 'count', 'damage'. 'maxDamage' is the discriminator: "
            + "WITH 'maxDamage' the item wears and 'damage' counts WEAR UPWARD FROM 0 toward "
            + "'maxDamage' -- NOT durability remaining, which is 'maxDamage'-'damage', so "
            + "{\"item\":\"diamond_pickaxe\",\"damage\":1520,\"maxDamage\":1561} is nearly WORN OUT "
            + "with 41 left, not nearly full; WITHOUT 'maxDamage' the item cannot wear at all and "
            + "the same field is vanilla variant METADATA (which stone_slab, which dye), so "
            + "'damage':3 there is a SUBTYPE, not wear. Never read an absent 'maxDamage' as "
            + "\"undamaged\" -- an Unbreakable tool omits it too, and likewise never wears. "
            + "TARGET. 'hitType' is \"block\"|\"entity\"|\"miss\" and decides which keys exist: a "
            + "block hit adds 'block' (registry name), 'pos' as ABSOLUTE [x,y,z] and 'side'; an "
            + "entity hit adds 'entityId', 'entityType' and 'entityHp' ROUNDED to whole HP. So "
            + "read 'hitType' before you read 'distance': on \"miss\" it is a hardcoded "
            + "0.0 meaning NO TARGET AT ALL, not a target at zero range, and a miss carries "
            + "neither block nor entity keys. 'distance's ORIGIN DEPENDS ON 'hitType' -- EYE to "
            + "the exact hit point on the face for a block, FEET TO FEET for an entity -- so the "
            + "numbers are NOT comparable across them. The block raytrace is bounded by vanilla's "
            + "reach (4.5 blocks in survival, 5.0 in creative), so a block hit is already in "
            + "reach: the number is your margin, not permission to dig. 'side' is the face you "
            + "look at and the face a placement goes against, and 'pos' is ABSOLUTE, not "
            + "grid-relative dx/dz. "
            + "ENV. 'daytime' is the game's OWN day/night, 'timeOfDay' only a coarse label that goes "
            + "stale near dawn and dusk, and a null there is a FAILED read, not a false value or a 0. "
            + "DIFF. An absent key means UNCHANGED, and a section your 'sections' list left out "
            + "answers 'unsampled':true rather than going quiet. 'entities.left' does NOT mean the "
            + "entity is gone: it is a statement about SAMPLING. If 'sections' left entities out "
            + "then NOTHING is reported left, and 'capped':true beside the list — the same fact as "
            + "'entitiesCapped' in full mode — ties truncation to the entity cap (sparse 5, explore "
            + "12, combat 24 nearest): a closer entity EVICTED a "
            + "farther one, so a cap read off the wrong profile mistakes an eviction for a "
            + "departure -- so re-observe with the same profile and radius before you believe a "
            + "threat is gone. An 'inventory' of {'unsampled':true} also means the read FAILED, "
            + "which is NOT an empty kit, so read 'cleared' as authoritative only where the section "
            + "was actually sampled. 'vel' is reported only past a 0.1 dead-band per axis, so size "
            + "your polling on 0.1; an effect flips to 'expiring' once, at 200 ticks remaining; and "
            + "'no effects' and 'could not read them' are DISTINCT, since a failed read "
            + "('unread':true) reports NOTHING as lost — treat 'unread' as 'ask again', never as "
            + "'your buffs ended'. 'xpProgress' is the 0..1 fraction of the way to your next level; "
            + "'air' is a countdown that goes NEGATIVE while drowning, so -1 is a real value, and "
            + "an explicit null means it just became unreadable where an absent key means "
            + "unchanged. After an unsampled or unread poll the next successful one sends the whole "
            + "set as '<section>':{'now':[...]}. "
            + "Call world_view{explain:'grid'|'entities'|'inventory'|'target'|'env'|'diff'} for the "
            + "rest of the legend instead of a world sample: the column run-length 'profile', the "
            + "damage and distance derivations, what the weather and light keys decide, and the "
            + "whole diff encoding. Fetch one before acting on a value you have not read.";

    /** Section name -> legend text, in the order {@code explain} accepts and reports them. */
    private static final Map<String, String> SECTIONS_TEXT = sections();

    private static Map<String, String> sections() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("grid", GRID);
        m.put("entities", ENTITIES);
        m.put("inventory", INVENTORY);
        m.put("target", TARGET);
        m.put("env", ENV);
        m.put("diff", DIFF);
        return m;
    }

    /** The section names {@code explain} accepts, in presentation order. */
    public static List<String> sectionNames() {
        return List.copyOf(SECTIONS_TEXT.keySet());
    }

    /**
     * The legend for one section, or {@code null} when the name is not a section.
     *
     * <p>{@code null} rather than an error string so the caller can REFUSE a bad name with the
     * same wording it already uses for a bad {@code sections} entry: an unrecognised argument
     * named in the reply is a recoverable turn, and one silently ignored is not.
     */
    public static String explain(String section) {
        return section == null ? null : SECTIONS_TEXT.get(section.trim().toLowerCase(Locale.ROOT));
    }

    /** Total characters held in the on-demand sections — the part no turn pays for. */
    public static int deferredChars() {
        int n = 0;
        for (String s : SECTIONS_TEXT.values()) {
            n += s.length();
        }
        return n;
    }

    // ---- GRID: what the header's value tables deliberately leave out -------------------

    private static final String GRID =
            "GRID, continued. 'profile' is the run-length encoding of the whole sampled vertical "
            + "window as [block,fromDy,length] triples, dy measured from your feet like surfaceDy. "
            + "The header says what an absent one means; this is what a present one contains. "
            + "'drop' is a whole-block LOWER bound: the probe stops at the first solid block, so a "
            + "floor one below the sampled window reads one shallower than it really is.";

    // ---- ENTITIES -----------------------------------------------------------------------

    private static final String ENTITIES =
            "ENTITIES, continued. The header names the three caps; what a cap MEANS is that a "
            + "nearer arrival EVICTS a farther one, so the far one leaves the list without leaving "
            + "the world. 'capped' and 'entitiesCapped' are set only when the cap ACTUALLY fired, "
            + "so treat them as authoritative: a list that merely happens to be cap-long dropped "
            + "nothing, and a false flag there would send you hunting an eviction that never "
            + "happened. Changing 'profile' or 'radius' between two polls moves the range and cap "
            + "under you, and nothing in the payload knows you did it -- the header's re-observe "
            + "rule exists for exactly that.";

    // ---- INVENTORY ----------------------------------------------------------------------

    private static final String INVENTORY =
            "INVENTORY, continued. The header gives the direction; the unit is damage POINTS, not "
            + "actions: a tool spends 1 per block dug and 2 per entity hit, a sword the reverse, "
            + "and the stack is destroyed by the use that takes 'damage' past 'maxDamage'. The 41 "
            + "uses the header's example leaves is about one and a half trees, which is the number "
            + "the repair-or-replace decision actually turns on.";

    // ---- TARGET -------------------------------------------------------------------------

    private static final String TARGET =
            "TARGET, continued. The header gives both distance origins; what it cannot is how far "
            + "apart they are in practice. The entity distance is the bounding-box bottoms, not "
            + "eye-to-hit-point, so it can run over a block off for a mob above or below you, and "
            + "vanilla's own entity cut-off (3.0 eye-to-hit outside creative) is a different "
            + "measure that cannot be recovered from this one.";

    // ---- ENV -------------------------------------------------------------------------------

    /**
     * The one filter every sentence below was put through, and it is the header's own: a line
     * belongs here only if a model that never fetched it would DO something different.
     *
     * <p><b>Kept, and the action each one changes.</b> {@code lightAtPlayer} -- without it the model
     * cannot answer "can a hostile spawn where I am standing", so it either walks into a dark cell
     * at night or refuses to move. {@code raining} against {@code thundering} -- one does not imply
     * the other, and only thunder moves the spawn gate, so a rainy afternoon read as a dangerous
     * one is wrong in whichever direction the model guessed. {@code timeOfDay} against
     * {@code daytime} -- the payload contradicts itself near the two edges and the model has no way
     * to pick, so it picks arbitrarily. {@code "unknown"} on a name -- a model reading it as a
     * place concludes it has lost track of where it is. And an {@code "unknown"}
     * {@code timeOfDay} is on that list now, for the reason the sentence about {@code worldTime}
     * below used to deny.
     *
     * <p><b>Written down and then deleted, because the answer was no.</b> The 0.2 and 0.9
     * thresholds behind rain and thunder (both transcribed in {@code
     * AnEnvironmentReportsTheWeatherThatMovesTheSpawnGateTest}): they justify the separation, but
     * the model is handed booleans and cannot act on a threshold it never sees a fraction against.
     * The spawn gate's own light threshold, for the same reason -- the model is handed the light,
     * not the verdict.
     *
     * <p><b>{@code worldTime} WAS on that list, and the denial was wrong.</b> It said the counter
     * "changes no action a reader of the label beside it could not already take", which is true in a
     * full view and false in a diff: {@code WorldViewDiff.envDiff} reports this key precisely
     * because it moves every tick, so a clock read that FAILED and defaulted to {@code 0} shipped
     * a movement of the counter -- backwards, by however far the real clock had run -- on a poll
     * where nothing had happened at all. The key is on the wire to make movement visible, so it
     * was the one key a silent default had no business moving. {@code worldTime} is boxed now, and
     * a null there is a failed read like every other one; that sentence below is what makes the
     * {@code "unknown"} {@code timeOfDay} worth its chars.
     */
    private static final String ENV =
            "ENV, continued. What this section is FOR is one question: can a hostile spawn where you "
            + "are standing. 'lightAtPlayer' answers it: the light at your own feet, already NET of "
            + "the storm, so a noon reading of 5 under thunder needs no weather arithmetic on top. "
            + "'raining' and 'thundering' are separate facts on separate thresholds and neither "
            + "implies the other; only THUNDER moves that gate, taking ten of the fifteen where rain "
            + "takes nothing. "
            + "'timeOfDay' is a LABEL with round edges and 'daytime' is the game's own curve, whose "
            + "night is the WIDER at both ends — vanilla turns at worldTime 13807 and 22193, so for "
            + "about 800 ticks at each end of it the sun is still up. An 'unknown' dimension or "
            + "biome name means the READ FAILED, not that the world has none — 'unknown' on "
            + "'timeOfDay' says the same about the clock. Every key here is always present, so "
            + "there is no absent case to decode. ";

    // ---- DIFF ----------------------------------------------------------------------------

    private static final String DIFF =
            "DIFF, continued. A diff is computed against your previous view ALONE, so every claim "
            + "here is relative to that one view: the FIRST diff after a full call has a baseline, "
            + "and the first ever call has none and comes back as full. An 'unsampled', a 'capped', "
            + "or a left arriving with an entered are all signatures of resampling rather than of "
            + "anything dying. "
            + "The header gives the thresholds; here is why they are there. The 'vel' band sits "
            + "above the idle jitter of gravity against ground friction, and a real fall clears it "
            + "within two ticks. 'expiring' fires once on crossing 200 ticks remaining -- vanilla's "
            + "own edge, where night vision starts to flicker -- so a poll coarser than that can "
            + "miss it, and the duration from when the effect was gained is the fallback. "
            + "'effects' is an object of 'gained' / 'lost' / 'expiring' keyed by potion, and a "
            + "duration merely TICKING DOWN is deliberately NOT a change: you already know what you "
            + "drank, so the diff gives you the part you cannot see. A stronger effect replacing a "
            + "weaker one reports as GAINED rather than lost+gained, because vanilla raises the "
            + "amplifier on the same effect. "
            + "'xpProgress' is compared EXACTLY, with no dead-band, because it only moves when XP is "
            + "picked up or spent -- it does not drift, so unlike 'vel' it never needs one.";
}