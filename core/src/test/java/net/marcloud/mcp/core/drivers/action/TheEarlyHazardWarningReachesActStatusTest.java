package net.marcloud.mcp.core.drivers.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.ActTickLoop;
import net.marcloud.mcp.core.drivers.act.FakeActuator;
import net.marcloud.mcp.core.drivers.act.MoveApplier;
import net.marcloud.mcp.core.drivers.act.NavIntent;
import net.marcloud.mcp.core.io.http.Json;
import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

import org.junit.Before;
import org.junit.Test;

/**
 * The straight-line hazard has to arrive at the caller as a VALUE on {@code act_status}, while the
 * walk is still going.
 *
 * <p>This is the other half of
 * {@code AHazardIsReadableBeforeTheWalkArrivesTest}, which proves the controller publishes the
 * hazard early. Publishing it is not the same as DELIVERING it: the chain between them is an
 * outcome, an applier, a slot record and a runtime snapshot, and a hazard dropped anywhere along
 * it is a hazard the agent never sees -- while every test closer to the controller still passes.
 * That is the shape of bug this file exists for, so it drives the real tool handler over a real
 * runtime with the real applier attached.
 *
 * <p>What is pinned, in order of how much it matters:
 * <ol>
 *   <li>the hazard is in the payload on an EARLY tick, with the slot still ACTIVE and the player
 *       not yet at the destination -- the advance warning, at the only place the model reads it;
 *   <li>{@code blocksAhead} is present and is a number, because "there is a hazard" without a
 *       distance does not tell the caller whether cancelling is still cheap;
 *   <li>the kind is the enum NAME, because that is the value a caller branches on;
 *   <li>the distance FALLS between two polls taken a few ticks apart, so the warning visibly
 *       closes rather than being a fixed decoration;
 *   <li>a slot that is not walking reports {@code null}, so "no hazard" cannot be confused with
 *       "the field is missing".
 * </ol>
 *
 * <p>No test-compile dependency on a private constant and no reliance on the prose: the assertions
 * read the JSON keys, so a reworded message cannot keep them green.
 */
public class TheEarlyHazardWarningReachesActStatusTest {

    private static final int TARGET_X = 30;
    private static final int LAVA_X = 12;
    /** One walking tick of a normal player's ~4.2 blocks/second. */
    private static final double STEP = 0.25;

    private GameClock clock;
    private ActRuntime runtime;
    private ActTickLoop loop;
    private ActTools tools;
    private FakeActuator act;

    @Before
    public void setUp() {
        clock = new GameClock();
        clock.reset();
        runtime = new ActRuntime(clock);
        act = new FakeActuator();
        // +x line at yaw -90 means W walks along it; at yaw 0 the same heading is a strafe press.
        act.setPosition(0.5, 64.0, 0.5);
        act.setRotation(-90f, 0f);
        for (int x = 0; x <= TARGET_X; x++) {
            act.putBlock(x, 63, 0);
        }
        act.putBlock(LAVA_X, 64, 0, "lava");
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier(act, runtime, null));
        loop = new ActTickLoop(runtime);
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

    private static Map<String, Object> parseJson(String json) {
        Map<String, Object> m = Json.readObject(json);
        assertNotNull("top-level JSON is an object", m);
        return m;
    }

    /** The MOVE row of an {@code act_status} payload, read through the real handler. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> moveRow() {
        Map<String, Object> out = parseJson(text(call(tools.actStatus(), Map.of())));
        List<Object> slots = (List<Object>) out.get("slots");
        return (Map<String, Object>) slots.get(ActSlot.MOVE.ordinal());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> hazardOf(Map<String, Object> moveRow) {
        return (Map<String, Object>) moveRow.get("hazard");
    }

    /**
     * One game tick, driven through the REAL
     * {@link net.marcloud.mcp.core.drivers.act.ActTickLoop}.
     *
     * <p>Not an inlined applier call. The loop is where the effective-tick gate and the
     * compare-and-store live, and a harness that skipped them would test a runtime nobody ships:
     * an applier is pure, so nothing reaches {@code act_status} until the loop commits the record
     * it returned. The loop is public and takes a synthetic {@link TickEvent} precisely so it can
     * be driven without a live game.
     *
     * <p>The player is then advanced one walking step, standing in for vanilla acting on the axes
     * the applier published -- which the fake cannot do for itself.
     */
    private void tickOnce() {
        long tick = clock.advance();
        loop.onTick(new TickEvent(tick));
        act.setPosition(act.position()[0] + STEP, act.position()[1], act.position()[2]);
    }

    /** A tick with NO player movement, for reading the state the reaction pause leaves behind. */
    private void stillTick() {
        long tick = clock.advance();
        loop.onTick(new TickEvent(tick));
    }

    @Test
    public void theHazardIsInActStatusOnAnEarlyTickWithTheWalkStillRunning() {
        runtime.submitNav(new NavIntent(TARGET_X, 64, 0, 400));
        tickOnce();

        Map<String, Object> row = moveRow();

        assertEquals("the walk has to be genuinely mid-flight for this to mean anything",
                "ACTIVE", row.get("phase"));
        Map<String, Object> hazard = hazardOf(row);
        assertNotNull("act_status must carry the hazard while the walk is running, not only after "
                + "it ends. This is the whole defect audit 2.3 was left with: a warning the model "
                + "can only read once the player has already fallen in. Row was " + row, hazard);
        assertEquals("and the kind must be the enum NAME, because that is what a caller branches "
                        + "on: " + hazard, "LAVA", hazard.get("kind"));
        assertEquals(LAVA_X, ((Number) hazard.get("x")).intValue());
        assertEquals(64, ((Number) hazard.get("y")).intValue());
        assertEquals(0, ((Number) hazard.get("z")).intValue());
        assertTrue("and a DISTANCE, in blocks: 'a hazard exists' does not say whether cancelling "
                        + "is still cheap, which is the only thing an advance warning is for. "
                        + "Hazard was " + hazard,
                hazard.get("blocksAhead") instanceof Number
                        && ((Number) hazard.get("blocksAhead")).doubleValue() > 0.0);
        assertNotNull("the consequence belongs on the wire too, or the model has to know what lava "
                + "does unaided: " + hazard, hazard.get("detail"));

        // And the walk really is still going: far more than ARRIVE_EPSILON remains.
        assertTrue("the player must still be short of the destination: " + row.get("message"),
                row.get("message").toString().contains("blocks to go"));
    }

    @Test
    public void theWarningClosesBetweenPollsRatherThanRepeatingAFixedNumber() {
        runtime.submitNav(new NavIntent(TARGET_X, 64, 0, 400));
        tickOnce();
        double first = ((Number) hazardOf(moveRow()).get("blocksAhead")).doubleValue();

        // Past the reaction pause, so the player is genuinely moving between the two polls.
        for (int i = 0; i < 12; i++) {
            tickOnce();
        }
        Map<String, Object> hazard = hazardOf(moveRow());
        assertNotNull("still on the line, so still warned: " + hazard, hazard);
        double later = ((Number) hazard.get("blocksAhead")).doubleValue();

        assertTrue("a warning whose distance never moves cannot say whether it is time to cancel: "
                + first + " then " + later, later < first);
        assertTrue("and it must still be a positive distance, not already behind: " + later,
                later > 0.0);
    }

    @Test
    public void aSlotThatIsNotWalkingReportsNoHazardAtAll() {
        // null, not an empty object and not a missing key: "nothing is known" and "this build does
        // not emit the field" must not look alike to a caller branching on it.
        Map<String, Object> row = moveRow();
        assertTrue("the MOVE slot must actually be empty for this to mean anything: " + row,
                row.containsKey("hazard"));
        assertNull("an unused slot reports no hazard: " + row, hazardOf(row));

        Map<String, Object> interact = null;
        Map<String, Object> out = parseJson(text(call(tools.actStatus(), Map.of())));
        @SuppressWarnings("unchecked")
        List<Object> slots = (List<Object>) out.get("slots");
        interact = (Map<String, Object>) slots.get(ActSlot.INTERACT.ordinal());
        assertNull("and so does an unrelated channel, which never walks a line at all: " + interact,
                hazardOf(interact));
    }

    @Test
    public void theTerminalRowStillCarriesTheHazardThatEndedTheWalk() {
        // The walk is what ends; this is the report of it. After the player has walked into lava a
        // terminal row that says only "arrived" leaves nothing to act on and nothing to learn from.
        runtime.submitNav(new NavIntent(TARGET_X, 64, 0, 400));
        String phase = null;
        for (int i = 0; i < 200; i++) {
            tickOnce();
            phase = moveRow().get("phase").toString();
            if (!"ACTIVE".equals(phase)) {
                break;
            }
        }

        Map<String, Object> row = moveRow();
        assertFalse("the walk must have ended for this to test anything: " + row,
                "ACTIVE".equals(row.get("phase")));
        Map<String, Object> hazard = hazardOf(row);
        assertNotNull("the terminal row must still say what the line held: " + row, hazard);
        assertEquals("and name the cause: " + hazard, "LAVA", hazard.get("kind"));
    }

    @Test
    public void theDistanceIsSignedSoAPassedHazardDoesNotReadAsAhead() {
        runtime.submitNav(new NavIntent(TARGET_X, 64, 0, 400));
        Double blocks = null;
        for (int i = 0; i < 200; i++) {
            tickOnce();
            Map<String, Object> hazard = hazardOf(moveRow());
            if (hazard != null) {
                blocks = ((Number) hazard.get("blocksAhead")).doubleValue();
                if (blocks < 0.0) {
                    break;
                }
            }
        }

        assertNotNull("the hazard must survive the whole walk", blocks);
        assertTrue("and must go NEGATIVE once the player has walked past it, so a caller reading "
                + "\"2.4 blocks ahead\" is never told a cell behind them is in front", blocks < 0.0);
    }

    @Test
    public void theDescriptionNamesTheHazardFieldSoTheModelKnowsWhatItIs() {
        // The vocabulary is derived from what the handler emits, so a field added without being
        // documented fails here rather than shipping as a key the model cannot interpret.
        String desc = tools.actStatus().tool().description();
        for (String key : new String[] {"hazard", "kind", "blocksAhead", "LAVA", "WATER",
                "DEEP_DROP"}) {
            assertTrue("act_status emits/uses '" + key + "' but the description never names it",
                    desc.contains(key));
        }
        assertTrue("and it must say the warning is EARLY, because that is the whole point of the "
                + "field and a model that believes it is a postmortem will not poll for it",
                desc.contains("EARLY WARNING"));
        assertFalse("while it must NOT claim the walk refuses to move, because walk_straight never "
                        + "does and a model that believed it would wait for a refusal that never "
                        + "comes",
                desc.contains("will refuse"));
    }
}
