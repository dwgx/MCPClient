package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

/**
 * The {@code env} section was the one payload section the legend never described.
 *
 * <p><b>What was invisible.</b> {@code WorldViewJson.envMap} emits eight keys. Four of them --
 * {@code raining}, {@code thundering}, {@code daytime}, {@code lightAtPlayer} -- had no name
 * anywhere in the text a model can reach, so a model had no reason to look for them, and
 * {@code timeOfDay} and {@code worldTime} were collapsed into the word "time" by a header that read
 * {@code env (dimension/biome/time)}. {@code world_view{explain:...}} had no {@code env} section to
 * fetch even if the model had suspected one existed.
 *
 * <p><b>Why that is a defect and not a style preference.</b> A legend line earns its place when the
 * NAIVE reading of the value produces a DIFFERENT ACTION -- the rule {@code WorldViewLegend}'s own
 * javadoc states and {@link WorldViewDescriptionBudgetTest} enforces for the grid, inventory and
 * target sections. Env had the same shape of hazard and none of the text: a payload that contradicts
 * itself near dawn and dusk, and a key carrying the answer to "can a hostile spawn here".
 *
 * <p><b>Three assertions, three disjoint red sets.</b> Each reads a DIFFERENT artefact, so a defect
 * in one is not caught by the others and a guard that rejects everything passes none of them for
 * the right reason:
 *
 * <ul>
 *   <li>{@link #everyEnvironmentKeyThePayloadEmitsIsNamedWhereTheAgentCanReadIt} reads
 *       {@link WorldViewJson#envMap}'s own key set and asks whether reachable text names each one.
 *       Red when a key is emitted and unnamed; GREEN if every sentence about those keys' MEANING is
 *       deleted.</li>
 *   <li>{@link #theHeaderSaysWhichOfTheTwoDayNightKeysIsTheGamesOwn} reads only
 *       {@link WorldViewLegend#HEADER}. Red when the always-on rules are missing; GREEN if the
 *       deferred section is deleted, because it never looks at one.</li>
 *   <li>{@link #theEnvironmentSectionCarriesOnlyWhatChangesAnAction} reads only
 *       {@code explain("env")}. Red when the section is gone or trimmed to trivia; GREEN if the
 *       header is edited, because it never looks at one.</li>
 * </ul>
 */
public final class TheEnvironmentSectionIsNotInvisibleToTheAgentTest {

    /**
     * A night the clock can answer and nothing else can: the shape the payload takes when the
     * weather and light reads fail, which is the state the joint omission used to hide.
     */
    private static final EnvView A_NIGHT = new EnvView("Overworld", "Plains", "night", 18000L,
            null, null, Boolean.FALSE, null);

    /** The same night with everything readable. Its difference from {@link #A_NIGHT} is the point. */
    private static final EnvView A_CLEAR_NIGHT = new EnvView("Overworld", "Plains", "night", 18000L,
            Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, 4);

    @Test
    public void everyEnvironmentKeyThePayloadEmitsIsNamedWhereTheAgentCanReadIt() {
        String reachable = allReachableText();
        Set<String> emitted = new LinkedHashSet<>(WorldViewJson.envMap(A_NIGHT).keySet());
        emitted.addAll(WorldViewJson.envMap(A_CLEAR_NIGHT).keySet());
        assertTrue("the fixture must exercise every env key or this proves nothing: " + emitted,
                emitted.containsAll(Set.of("dimension", "biome", "timeOfDay", "worldTime", "raining",
                        "thundering", "daytime", "lightAtPlayer")));
        for (String key : emitted) {
            assertTrue("world_view emits env key '" + key + "' but no text the agent can reach "
                    + "names it as a quoted key, so the model has no legend for it at all",
                    reachable.contains("'" + key + "'"));
        }
    }

    /**
     * The two rules whose naive reading changes what the agent DOES, and which therefore cannot
     * wait for a fetch: the agent has already read {@code timeOfDay} and moved by the time it
     * thinks to ask.
     */
    @Test
    public void theHeaderSaysWhichOfTheTwoDayNightKeysIsTheGamesOwn() {
        String h = WorldViewLegend.HEADER;
        assertTrue("the header must name 'daytime' as the game's own day/night, because "
                + "'timeOfDay' is a coarse label that goes stale near dawn and dusk and a model "
                + "given both with no rule between them picks one arbitrarily",
                h.contains("'daytime' is the game's OWN day/night"));
        assertTrue("and must say which of the two goes stale, so the direction of the "
                + "disagreement is not a guess either", h.contains("coarse label that goes"));
        assertTrue("and must say a null is a FAILED read: read as false it is 'it is definitely "
                + "not raining', which is how a read that never happened becomes a fact",
                h.contains("a null there is a FAILED read, not a false value"));
    }

    /**
     * What the deferred section carries.
     *
     * <p>Every clause below is asserted because a model that never fetched this one does something
     * DIFFERENT: it cannot answer "can a hostile spawn here", it guesses whether rain darkens the
     * world, and it reads an {@code "unknown"} biome as a place rather than as a failed read. The
     * section must NOT restate the header -- the header's null rule is asserted above and is
     * deliberately absent here.
     */
    @Test
    public void theEnvironmentSectionCarriesOnlyWhatChangesAnAction() {
        String env = WorldViewLegend.explain("env");
        assertNotNull("explain='env' must resolve: the environment section shipped with no legend "
                + "at all, and the enum already offers the name", env);
        assertTrue("and the model must be told what the section is FOR before any of it lands: "
                + env, env.contains("can a hostile spawn where you are standing"));
        assertTrue("'lightAtPlayer' is the spawn-gate light at your own feet and is already net of "
                + "the storm, so the model must not add weather arithmetic on top of it: " + env,
                env.contains("already NET of"));
        assertTrue("rain and thunder are separate facts and only thunder moves the spawn gate, so "
                + "'raining':true must not be read as the world having darkened: " + env,
                env.contains("neither implies the other"));
        assertTrue("and the label's night must be quantified, because 'near dawn and dusk' is not "
                + "a range the model can plan around: " + env, env.contains("13807"));
        assertTrue("'unknown' on a dimension or biome is a failed read, not a place name: " + env,
                env.contains("An 'unknown' dimension or biome name means the READ FAILED"));
        assertTrue("and the absence state the header cannot mention -- the payload used to be able "
                + "to omit all four weather keys at once -- has to be declared gone: " + env,
                env.contains("no absent case to decode"));
        assertTrue("the section must not repeat the header's null rule; the two halves do not "
                + "overlap, and a repeat is per-fetch tax for a sentence already sent every turn",
                !env.contains("not a false value"));
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
}