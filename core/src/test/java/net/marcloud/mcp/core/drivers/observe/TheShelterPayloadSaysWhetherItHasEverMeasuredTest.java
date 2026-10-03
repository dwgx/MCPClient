package net.marcloud.mcp.core.drivers.observe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;
import java.util.TreeSet;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import org.junit.Test;

import net.marcloud.mcp.core.eval.Enclosure;
import net.marcloud.mcp.core.eval.NightEnclosure;
import net.marcloud.mcp.core.eval.NightShelter;
import net.marcloud.mcp.core.io.http.Json;

/**
 * {@code night_shelter} has to be able to say whether it has EVER observed anything.
 *
 * <p><b>The defect this pins.</b> {@code NightShelter} gated every accessor on one private flag,
 * {@code lastTickSeen}, and published none of it. Fifteen of the sixteen payload keys were gated on
 * it, so three states that need opposite answers were byte-identical:
 *
 * <ul>
 *   <li><b>A</b> — armed, ticked, daytime: {@code measured=false}, every counter at its initial
 *       value.</li>
 *   <li><b>B</b> — armed, the seam IS delivering ticks, the client has no world yet (title screen,
 *       world loading, just left one). {@code NightShelter.onTick}'s no-body early return sits
 *       BEFORE {@code lastTickSeen = true}, so this is a steady state, not a race, and it lasts for
 *       the whole of the title screen.</li>
 *   <li><b>C</b> — un-armed ({@code -Dmcp.core.shelter=false}) or no seam ({@code
 *       -Dmcp.core.tick=false}, or no {@code -javaagent}, which {@code McpCore} documents as "MCP
 *       still serves — never fatal"). {@code onTick} is never called.</li>
 * </ul>
 *
 * In state A a model should read the counters. In states B and C every counter is a placeholder
 * and {@code exposedNow:false} means "not looked at", never "safe" — which is the reading the
 * shipped description actively pushed towards, because its FIELDS section taught "read
 * sampledLastTick alongside exposedNow", and {@code sampledLastTick} is false in all three states.
 * So the naive rule resolved "never observed" to "the previous tick was safe".
 *
 * <p><b>Why the key is asserted as a String and never as a method call.</b> This file names the
 * payload key only as text and reads {@code fact()} for the attribution, so it COMPILES against the
 * tree that shipped without either. A red that is a compile error proves a method is absent; a red
 * that is an assertion proves the payload does not carry the answer, which is the defect. The same
 * choice is what makes the mutation meaningful: delete the {@code out.put} from
 * {@code ShelterTools} and this goes red on a key the getter still answers.
 *
 * <p><b>What the key does and does not separate.</b> It separates A from {B, C} at the
 * machine-readable level, which is the whole of the false-success surface. B and C still share it,
 * and {@link #theTwoCausesGetTwoSentencesRatherThanOneBlame} covers what is left: that
 * distinction lives in {@code fact} and is asserted as prose on purpose, because a second boolean
 * for it would be a bit whose only consumer parses the sentence next to it anyway.
 *
 * <p><b>Nothing here pins a computation.</b> {@code exposedNow()} already gates on
 * {@code lastTickSeen} (a real fix from an earlier slice), so no arithmetic moved — what moved is
 * that the gate became visible. {@link #theKeyIsTrueOnceTheCounterHasMeasuredAndFalseUntilThen} is
 * what keeps this from being a constant: the same key is asserted true in the daytime state where
 * it and {@code measured} disagree, which is the state the new FIELDS text exists to disambiguate.
 */
public final class TheShelterPayloadSaysWhetherItHasEverMeasuredTest {

    /** The payload key. Text on purpose — see the class javadoc. */
    private static final String KEY = "measuredOnce";

    /**
     * The sentence {@code fact()} used to print for BOTH causes, and which is therefore false in
     * state B: the seam is delivering ticks there, demonstrably, because {@code onTick} is being
     * called to print the sentence at all.
     */
    private static final String BLAMED_ON_THE_SEAM = "the tick seam has not delivered a tick yet";

    /**
     * The per-turn ceiling for this description alone, so the rules this slice added cannot be
     * paid for later by deleting a rule that changes an action. Measured 2026-10-03: 2,262 chars
     * before, 2,815 after — the growth is the key's own entry, {@code fact}'s entry, and the
     * joint reading of {@code exposedNow} with the key.
     */
    private static final int DESCRIPTION_BUDGET = 3_000;

    // ===== the headline: state B, which the shipped payload could not tell from state C =====

    @Test
    public void aTickingClientWithNoWorldSaysItHasNeverMeasuredAndDoesNotBlameTheSeam() {
        NightShelter shelter = titleScreen();

        Map<String, Object> payload = payloadOf(shelter);
        String fact = String.valueOf(payload.get("fact"));

        assertTrue("the payload must carry '" + KEY + "', or a model has no way to tell a live"
                + " ledger from an empty one and falls back on the counters, which in this state"
                + " are all initial values that read exactly like a safe body. Keys: "
                + payload.keySet(),
                payload.containsKey(KEY));
        assertEquals("state B: ticks are arriving and none could be measured, so this is false —"
                + " and 'false' here is 'nothing was ever observed', not 'safe'",
                Boolean.FALSE, payload.get(KEY));

        assertFalse("and the sentence must stop blaming a seam that is demonstrably delivering"
                + " ticks: " + fact, fact.contains(BLAMED_ON_THE_SEAM));
        assertTrue("it must say the ticks ARE arriving instead, because that cause has a different"
                + " fix (wait for the world) than the other one (the counter is not attached): "
                + fact, fact.contains("ticks ARE arriving"));
        assertTrue("and it must stay in the NOT MEASURED family every unmeasured state uses, so a"
                + " reader scanning for it still finds it: " + fact,
                fact.contains("NOT MEASURED"));

        // The rest of the payload really is still indistinguishable from state C. Asserted rather
        // than left as a comment, because it is the reason `fact` had to be split: if a future key
        // ever separates these two, this goes red and someone has to decide whether that is right.
        Map<String, Object> neverTicked = payloadOf(new NightShelter());
        TreeSet<String> differing = new TreeSet<>();
        for (String k : payload.keySet()) {
            if (!java.util.Objects.equals(payload.get(k), neverTicked.get(k))) {
                differing.add(k);
            }
        }
        assertEquals("state B and state C are expected to differ in the one sentence that names"
                + " the cause and nowhere else — the new key deliberately does NOT split them,"
                + " because telling them apart is a prose question",
                java.util.Collections.singleton("fact"), differing);
    }

    // ===== the two causes, which the shipped sentence merged =====

    @Test
    public void theTwoCausesGetTwoSentencesRatherThanOneBlame() {
        String tickingNoWorld = titleScreen().fact();
        String neverTicked = new NightShelter().fact();

        assertNotEquals("a client ticking with no world and a counter that was never attached are"
                + " different faults with different fixes, so they must not share a sentence",
                neverTicked, tickingNoWorld);
        assertTrue("the un-armed one names the seam, because there the seam really is silent: "
                + neverTicked, neverTicked.contains(BLAMED_ON_THE_SEAM));
        assertTrue("and the other names the missing world instead: " + tickingNoWorld,
                tickingNoWorld.contains("no world"));
    }

    // ===== non-vacuity: the key has to be able to be true =====

    @Test
    public void theKeyIsTrueOnceTheCounterHasMeasuredAndFalseUntilThen() {
        // Daytime: the counter is alive and has never sampled a NIGHT tick. This is the state the
        // new FIELDS text exists to disambiguate, and the only thing here that proves the new key
        // is not a rename of `measured`.
        NightShelter daytime = new NightShelter().reading(() -> body(worldIdentity(), 1_000L, AIR));
        daytime.onTick();
        Map<String, Object> day = payloadOf(daytime);

        assertEquals("daylight: the counter HAS measured, at ticks that carried no night sample",
                Boolean.TRUE, day.get(KEY));
        assertEquals("and `measured` is still false there — if these two ever agree the new key is"
                + " redundant, and the FIELDS text is lying about needing both",
                Boolean.FALSE, day.get("measured"));

        // One tick on the far side of dusk over an all-air grid: a real, open, unsealed sample.
        NightShelter night = new NightShelter()
                .reading(() -> body(worldIdentity(), NightEnclosure.duskTick(), AIR));
        night.onTick();
        Map<String, Object> afterNight = payloadOf(night);

        assertEquals("once a night tick has been sampled both keys agree", Boolean.TRUE,
                afterNight.get("measured"));
        assertEquals("with the new key still true — it is a latch and never clears", Boolean.TRUE,
                afterNight.get(KEY));
        assertEquals("so `exposedNow:false` in the states above really does mean two different"
                + " things ('not tonight' and 'not ever'), which is why the description now has to"
                + " be read with the key rather than on its own",
                Boolean.TRUE, afterNight.get("exposedNow"));
    }

    // ===== the description, which is the half a model that reads the prose actually gets =====

    @Test
    public void theDescriptionNamesTheKeyTheSentenceAndTheJointReadingOfExposedNow() {
        String d = description();

        assertTrue("the FIELDS list must name the key, or a key the payload carries is a key"
                + " nobody is told about: " + d, d.contains(KEY));
        assertTrue("it must say what false means, in the terms that change the action and not"
                + " merely that it is false: " + d, d.contains("NEVER held a single"));
        assertTrue("and it must cover exposedNow specifically, because that is the field a model"
                + " reaches for when it wants to know about right now, and false there is the"
                + " answer that reads as 'safe': " + d, d.contains("exposedNow:false"));
        assertTrue("`fact` was the ONLY carrier of the two causes and it was absent from FIELDS"
                + " entirely, so a model reading the field list had no way to find it: " + d,
                d.contains("fact (one sentence"));
        assertTrue("and the joint reading has to be stated, because the old text taught the joint"
                + " read with sampledLastTick alone, which resolves to 'the last tick was safe' in"
                + " exactly the state this key exists for: " + d,
                d.contains("read " + KEY + " and sampledLastTick alongside it"));
    }

    @Test
    public void theDescriptionStaysInsideItsPerTurnBudget() {
        String d = description();
        assertTrue("night_shelter's description is " + d.length() + " chars, over its "
                + DESCRIPTION_BUDGET + " per-turn budget (2,262 before this slice). Both rules"
                + " added change an action — the key's own reading, and the joint read of"
                + " exposedNow with it. Cut prose that only explains, but do NOT delete either"
                + " rule to make a number pass, and do not delete " + KEY + " itself.",
                d.length() <= DESCRIPTION_BUDGET);
    }

    // ===== premises, so the headline cannot pass on a driver that never ran =====

    @Test
    public void theTitleScreenDriverReallyDeliveredTicksAndMeasuredNothing() {
        NightShelter shelter = titleScreen();
        assertEquals("the driver really did deliver ticks — the sentence changed, and only a"
                + " call to onTick can change it: " + shelter.fact(), 0, shelter.samples());
        assertTrue("and really measured nothing: " + shelter.fact(),
                shelter.fact().contains("ticks ARE arriving"));
    }

    @Test
    public void theEarlyReturnSitsAboveTheLatchSoATickingClientStillReadsUnmeasured() {
        // The mutation this whole file would miss: moving `lastTickSeen = true` above the no-body
        // early return in onTick. State B would then report itself MEASURED, every counter would
        // claim it had been looked at, and a title-screen client would be told it had sheltered.
        // The sentence is the witness -- it can only say "ticks ARE arriving" if the counter knows
        // a tick arrived while the key says nothing was ever measured.
        NightShelter shelter = titleScreen();

        assertTrue("the counter must know a tick was delivered: " + shelter.fact(),
                shelter.fact().contains("ticks ARE arriving"));
        assertEquals("and must still report nothing observed, because that tick had no world in"
                + " it and therefore took no sample",
                Boolean.FALSE, payloadOf(shelter).get(KEY));
        assertEquals("so the other gate is closed too, and a client with no world reads as"
                + " unexposed rather than as enclosed", Boolean.FALSE,
                payloadOf(shelter).get("shelteredSoFar"));
    }

    @Test
    public void aSupplierThatYieldsNoBodyIsTheSameStateAsAWorldlessOne() {
        // Both take the no-body early return with a tick already delivered, so both must land in
        // the same branch. Pinned because it is the path -Dmcp.core.shelter=false leaves behind if
        // anything ever wires the subscriber but not the body.
        NightShelter absent = new NightShelter().reading(() -> null);
        absent.onTick();

        assertEquals("unmeasured, like a worldless body", Boolean.FALSE,
                payloadOf(absent).get(KEY));
        assertEquals("and attributed the same way, because the tick DID arrive",
                titleScreen().fact(), absent.fact());
    }

    // ===== drivers =====

    /**
     * State B exactly as it occurs live: a {@link NightShelter.Body} that EXISTS, whose
     * {@code world()} is null because the client is not in a world. Not a null supplier — that is
     * the narrower sibling of the same early return, and this is the path {@code ClientBody}
     * actually takes on the title screen.
     */
    private static NightShelter titleScreen() {
        NightShelter shelter = new NightShelter().reading(() -> body(null, 0L, AIR));
        for (int tick = 0; tick < 40; tick++) {
            shelter.onTick();
        }
        return shelter;
    }

    /** A fresh world object, because world IDENTITY is the accumulator's reset trigger. */
    private static Object worldIdentity() {
        return new Object();
    }

    private static NightShelter.Body body(Object world, long worldTime, Enclosure.CellGrid grid) {
        return new NightShelter.Body() {
            @Override
            public Object world() {
                return world;
            }

            @Override
            public long worldTime() {
                return worldTime;
            }

            @Override
            public Enclosure.CellGrid grid() {
                return grid;
            }

            @Override
            public int feetX() {
                return 0;
            }

            @Override
            public int feetY() {
                return 64;
            }

            @Override
            public int feetZ() {
                return 0;
            }
        };
    }

    /** All air: the body is reachable from every side, so a night sample reads as EXPOSED. */
    private static final Enclosure.CellGrid AIR = (bx, by, bz) -> null;

    // ===== plumbing, mirroring ObserveToolsTest's handler helpers =====

    private static Map<String, Object> payloadOf(NightShelter shelter) {
        SyncToolSpecification spec = new ShelterTools(shelter).nightShelter();
        CallToolResult r = spec.callHandler()
                .apply(null, new CallToolRequest(spec.tool().name(), Map.of()));
        Map<String, Object> out = Json.readObject(text(r));
        assertNotNull("night_shelter must answer with a JSON object", out);
        return out;
    }

    private static String description() {
        return new ShelterTools(new NightShelter()).nightShelter().tool().description();
    }

    private static String text(CallToolResult r) {
        for (Content c : r.content()) {
            if (c instanceof TextContent t) {
                return t.text();
            }
        }
        fail("no text content in result");
        return null;
    }
}