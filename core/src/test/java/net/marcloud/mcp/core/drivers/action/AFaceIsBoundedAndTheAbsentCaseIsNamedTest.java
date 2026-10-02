package net.marcloud.mcp.core.drivers.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.InteractIntent;
import net.marcloud.mcp.core.ke.GameClock;

import org.junit.Before;
import org.junit.Test;

/**
 * {@code face} is a closed set of six, and the tool used to treat every value outside it --
 * including "no value at all" -- as {@code DOWN}, then answer {@code accepted: true}.
 *
 * <p><b>The chain, verified link by link.</b> {@code ActIntentParser.parseInteract} read the face
 * with {@code intArg(m, "face", -1)}, so an absent one became {@code -1} and an out-of-range one
 * stayed as written. {@code ActActuator.Face.fromIndex} mapped anything outside 0..5 to
 * {@code DOWN}. The caller therefore got the same dig, downward, for {@code face: 7} and for no
 * face at all -- and for a {@code place}, downward is not a dig, it is a block placed one cell
 * <i>below</i> the one named, because {@code ItemBlock.onItemUse} does
 * {@code pos = pos.offset(side)}.
 *
 * <p><b>Why the answer differs between the two cases, which is the judgement this file records.</b>
 * An out-of-range face and an absent face are the same symptom and are NOT the same defect, so
 * they do not get the same answer:
 *
 * <ul>
 *   <li><b>Out of range: REFUSED.</b> {@code 7} is not a near miss for {@code 5}; it is not a face
 *       at all, and for a placement it names a different cell. A clamp would pick a side the
 *       caller did not name, which is the failure, not a cure for it.</li>
 *   <li><b>Absent on {@code place}: REFUSED.</b> The facing there is the request: "against (x,y,z)"
 *       has six answers and DOWN is one of them. There is no default that is not an answer.</li>
 *   <li><b>Absent on {@code dig}: ACCEPTED, and named in the reply.</b> A dig breaks the block it
 *       names whichever side you approach it from -- {@code PlayerControllerMP.clickBlock} sends
 *       its packet against {@code loc} and only carries the facing inside it -- so refusing would
 *       break a call that does exactly what it says. The default is real, so it is REPORTED rather
 *       than hidden: {@code act_set}'s {@code assumptions} field names it.</li>
 * </ul>
 *
 * <p><b>Mutation, both directions.</b> Removing the bound (revert {@code faceArg} to a bare
 * {@code intArg(m,"face",-1)}) reddens {@link #aFaceOutsideTheSixIsRefusedRatherThanDugAsDown} and
 * {@link #anOutOfRangeFaceNeverReachesTheSlot} only. Blanket-refusing every dig that omits a face
 * reddens {@link #aDigWithoutAFaceStillDigsTheBlockItNames} and
 * {@link #everyValidFaceAndBothBlockKindsStillReachTheSlot} only. The two red sets are disjoint:
 * the first is about out-of-range values, the second is about the absent case.
 */
public class AFaceIsBoundedAndTheAbsentCaseIsNamedTest {

    private GameClock clock;
    private ActRuntime runtime;
    private ActTools tools;

    @Before
    public void setUp() {
        clock = new GameClock();
        clock.reset();
        runtime = new ActRuntime(clock);
        tools = new ActTools(runtime);
    }

    private static CallToolResult call(SyncToolSpecification spec, Map<String, Object> args) {
        return spec.callHandler().apply(null, new CallToolRequest(spec.tool().name(), args));
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

    private CallToolResult set(Map<String, Object> interact) {
        return call(tools.actSet(), Map.of("interact", interact));
    }

    private Map<String, Object> dig(Object face) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "dig");
        m.put("block", List.of(4, 64, 4));
        if (face != null) {
            m.put("face", face);
        }
        return m;
    }

    private InteractIntent submitted() {
        return (InteractIntent) runtime.record(ActSlot.INTERACT).intent();
    }

    /**
     * The whole neighbourhood of the bound, not one sample past it -- the same discipline
     * {@code ToolDescriptionsMatchTheirBoundsTest} uses on {@code do_select_slot}, because the one
     * value anybody ever tries is refused either way and a single sample proves nothing.
     */
    @Test
    public void aFaceOutsideTheSixIsRefusedRatherThanDugAsDown() {
        for (int face = -3; face <= 12; face++) {
            if (face >= 0 && face <= 5) {
                continue;
            }
            CallToolResult r = set(dig(face));
            String msg = text(r);
            assertTrue("face " + face + " is not one of the six faces a block has, and must be "
                    + "refused rather than dug as DOWN: " + msg, Boolean.TRUE.equals(r.isError()));
            assertTrue("the refusal must name the argument, or the caller cannot tell which of "
                    + "their arguments was wrong: " + msg, msg.contains("'face'"));
            assertTrue("and must print the legal set, because 'out of range' alone is not something "
                    + "a caller can fix in one resend: " + msg, msg.contains("0=down"));
        }
    }

    /**
     * Nothing may reach the slot on the refused path. Asserted separately from the refusal above so
     * that a fix which refused <i>after</i> submitting still fails: the reply would be an error and
     * the player would already be digging.
     */
    @Test
    public void anOutOfRangeFaceNeverReachesTheSlot() {
        assertTrue("setup: this call must be refused at all",
                Boolean.TRUE.equals(set(dig(7)).isError()));
        assertEquals("a refused face must leave the INTERACT slot empty -- an error reply over an "
                + "intent that is already running tells the model the opposite of what happened",
                null, submitted());
    }

    /** The placement case, which is where an absent face is a block in the wrong cell. */
    @Test
    public void aPlaceWithoutAFaceIsRefusedAndSaysWhichSideItDecides() {
        Map<String, Object> place = new LinkedHashMap<>();
        place.put("kind", "place");
        place.put("block", List.of(1, 2, 3));

        CallToolResult r = set(place);
        String msg = text(r);
        assertTrue("a placement with no face must be refused: " + msg,
                Boolean.TRUE.equals(r.isError()));
        assertTrue("the refusal must say the face decides WHICH CELL the block goes in, because "
                + "that is the fact the caller has to learn: " + msg,
                msg.contains("WHICH SIDE") || msg.contains("which cell"));
        assertTrue("and must print the six: " + msg, msg.contains("0=down"));
        assertEquals("and nothing may be submitted", null, submitted());
    }

    /**
     * The other half of the judgement, and the half that makes refusing a dig's absent face wrong.
     *
     * <p>This test is what reddens under a blanket refusal. It is also the reason the dig default
     * is reported rather than merely permitted: an accepted call that silently chose a facing is
     * the shape F1 exists to remove, so the reply has to name what was chosen.
     */
    @Test
    public void aDigWithoutAFaceStillDigsTheBlockItNames() {
        CallToolResult r = set(dig(null));

        assertFalse("a dig that names a block and omits a face does exactly what it says -- the "
                + "block named is the block dug -- so it must not be refused: " + text(r),
                Boolean.TRUE.equals(r.isError()));
        InteractIntent intent = submitted();
        assertNotNull("the dig must reach the slot", intent);
        assertEquals(InteractIntent.Kind.DIG, intent.kind());
        assertEquals("and it must be the block the caller named", 4, intent.blockX());
    }

    /** The default is not permitted silently: it is named in the reply. */
    @Test
    public void theReplyNamesTheFacingTheToolChoseForAnAbsentOne() throws Exception {
        String reply = text(set(dig(null)));

        assertTrue("the reply must carry an 'assumptions' field, because a default the caller "
                + "cannot see is the defect and a default it can see is a decision: " + reply,
                reply.contains("assumptions"));
        assertTrue("and that field must say a face was chosen on the caller's behalf: " + reply,
                reply.contains("no 'face' was given"));
        assertTrue("naming which face: " + reply, reply.contains("DOWN"));

        // The control: a face the caller DID supply must produce an empty list, or "assumptions"
        // is a field that always fires and therefore says nothing.
        String named = text(set(dig(3)));
        assertTrue("with a face supplied there is nothing to report, and 'assumptions' must be an "
                + "empty list rather than absent -- empty says 'we looked and chose nothing': "
                + named, named.contains("assumptions\":[]"));
    }

    /**
     * The other direction for the refusal: every legal face, and both kinds that read one, still
     * work. Reddens under a blanket refusal of anything touching a face.
     */
    @Test
    public void everyValidFaceAndBothBlockKindsStillReachTheSlot() {
        for (int face = 0; face <= 5; face++) {
            assertFalse("face " + face + " is one of the six and must be accepted: "
                    + text(set(dig(face))), Boolean.TRUE.equals(set(dig(face)).isError()));
            assertEquals("the face must survive to the intent unchanged",
                    face, submitted().face());

            Map<String, Object> place = new LinkedHashMap<>();
            place.put("kind", "place");
            place.put("block", List.of(1, 2, 3));
            place.put("face", face);
            assertFalse("place with face " + face + " must be accepted: " + text(set(place)),
                    Boolean.TRUE.equals(set(place).isError()));
            assertEquals(face, submitted().face());
        }
    }

    /**
     * The refusal is a refusal a caller can act on, and the schema has to say so before the call.
     *
     * <p>Derived from the enum, not from literals, so the two cannot drift: if a face is added,
     * this asks about all seven rather than passing against a stale list of six.
     */
    @Test
    public void theSchemaSaysWhatTheHandlerEnforcesInBothDirections() {
        Object props = ((Map<?, ?>) tools.actSet().tool().inputSchema()).get("properties");
        Object interact = ((Map<?, ?>) props).get("interact");
        Map<?, ?> face = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) interact).get("properties")).get("face");
        String desc = String.valueOf(face.get("description"));

        assertTrue("the schema must state the legal range, since a model reads the schema and not "
                + "the guard: " + desc, desc.contains("0=down"));
        assertTrue("and must say an out-of-range value is REFUSED rather than rounded, or the "
                + "model will keep sending it believing it was clamped: " + desc,
                desc.contains("REFUSED"));
        assertTrue("and must say 'place' REQUIRES one, because that is the half a caller cannot "
                + "guess: " + desc, desc.contains("REQUIRED on 'place'"));
        assertEquals("the declared bounds must still be the six, derived rather than typed",
                0, ((Number) face.get("minimum")).intValue());
        assertEquals(5, ((Number) face.get("maximum")).intValue());
    }
}