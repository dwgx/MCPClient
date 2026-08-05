package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.board.Trace;
import net.marcloud.mcp.board.signals.PacketSendSignal;
import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.GameBridge;
import net.marcloud.mcp.core.drivers.action.ActionManager;
import net.marcloud.mcp.core.drivers.world.DisconnectTracker;
import net.marcloud.mcp.core.drivers.world.PacketLog;
import net.marcloud.mcp.core.ke.KeGameDispatcher;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.DisconnectedEvent;
import net.marcloud.mcp.core.link.BoardTraceLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.util.ChatComponentText;
import org.junit.After;
import org.junit.Test;

/**
 * The other half of {@code ClickSlotDescriptionMatchesVanillaTest}'s subject. That file asks
 * whether a tool's prose is true of the PROTOCOL; this one asks whether it is still true of THIS
 * CODE. Every defect guarded here has one shape -- a legend the handler quietly stopped honouring
 * -- and the repo has paid for it three times already: a hotbar documented "slot 0-8" whose bound
 * admitted 11, a schema advertising "1-64" over a silent clamp, a sensor answering "nothing seen"
 * from a probe that was never installed. An absent legend leaves a model uncertain; a wrong one
 * makes it confidently wrong, and confidently wrong is what puts a dig at the world origin.
 *
 * <p>So no number here is hand-copied. Each bound is DERIVED -- drive the real handler across a
 * range, watch where it stops sending -- and only then compared with the number the description
 * prints. A literal 8 written into a test agrees with a stale description forever, including after
 * the code stops honouring it, which is precisely how these six survived the suite.
 *
 * <p>Three of them need a send path, because "reported sent" and "put on the wire" are exactly the
 * pair that drifts apart. {@link #husk} builds the smallest client
 * {@code ActionManager.sendRawPacket} will accept and nothing else.
 */
public class ToolDescriptionsMatchTheirBoundsTest {

    /** Same reach-a-registered-tool shape as {@code ClickSlotDescriptionMatchesVanillaTest}. */
    private static Tool tool(String name) {
        return spec(unwired(), name).tool();
    }

    private static SyncToolSpecification spec(ToolRegistry reg, String name) {
        for (SyncToolSpecification s : reg.all()) {
            if (s.tool().name().equals(name)) {
                return s;
            }
        }
        throw new AssertionError("tool not found: " + name);
    }

    /** No control surface: a handler that decides to send still gets as far as its label. */
    private static ToolRegistry unwired() {
        return new ToolRegistry(new ToolContext(null, null, null, null, null));
    }

    /** One handler call, flattened to the string a model would actually read. */
    private static String call(ToolRegistry reg, String name, Map<String, Object> args) {
        CallToolResult r = spec(reg, name).callHandler()
                .apply(null, new CallToolRequest(name, args));
        return (Boolean.TRUE.equals(r.isError()) ? "ERROR " : "OK ") + r.content();
    }

    /**
     * Whether the handler ACCEPTED these arguments, read off behaviour instead of off the error
     * text. {@code sendTyped} is the only place {@code label} is built, so a reply naming the label
     * came from past the validation gate. Matching on "must be 0-8" would be matching the very
     * string the audit says can outlive the bound it describes.
     */
    private static boolean reachedSendPath(String reply, String label) {
        return reply.contains(label);
    }

    /** The description a model reads for one input property, straight out of the schema. */
    private static String schemaProp(Tool t, String property) {
        Object props = ((Map<?, ?>) t.inputSchema()).get("properties");
        Object prop = ((Map<?, ?>) props).get(property);
        return String.valueOf(((Map<?, ?>) prop).get("description"));
    }

    /**
     * do_select_slot: the range the handler enforces must be the range its legend prints, and the
     * range vanilla actually has. The probe is the whole neighbourhood of the bound, not one sample
     * past it -- one sample is what let a bound of 11 sit under a legend of 0-8, since the single
     * value anybody ever tried (12) is refused either way.
     */
    @Test
    public void selectSlotSendsOnlyTheHotbarSlotsItsLegendPrints() {
        ToolRegistry reg = unwired();
        List<Integer> reachedWire = new ArrayList<>();
        for (int slot = -3; slot <= 20; slot++) {
            if (reachedSendPath(call(reg, "do_select_slot", Map.of("slot", slot)),
                    "held_item slot=" + slot)) {
                reachedWire.add(slot);
            }
        }

        // Vanilla's own hotbar width, so the expectation is not a literal that can agree with a
        // stale legend: C09 carries an index into these slots and nothing else exists to select.
        List<Integer> vanillaHotbar = new ArrayList<>();
        for (int i = 0; i < InventoryPlayer.getHotbarSize(); i++) {
            vanillaHotbar.add(i);
        }
        assertEquals("a slot past the hotbar goes out as a C09 the server cannot honour while the "
                + "tool answers 'sent', so a model believes it is holding an item it is not and "
                + "then digs, eats or attacks with the wrong hand", vanillaHotbar, reachedWire);

        int highest = reachedWire.get(reachedWire.size() - 1);
        String bound = "0-" + highest;
        Tool t = tool("do_select_slot");
        assertTrue("the description must print the range the handler enforces (" + bound + "); a "
                + "model reads the legend, not the guard, and will happily send whatever the "
                + "legend permits", t.description().contains(bound));
        assertTrue("the schema property must print the same range, because a model that reads only "
                + "the schema gets the same promise", schemaProp(t, "slot").contains(bound));
        assertFalse("no wider range may appear anywhere in the legend: naming a slot the handler "
                + "refuses sends the model into a retry loop against a value that can never work",
                t.description().contains("0-" + (highest + 1))
                        || schemaProp(t, "slot").contains("0-" + (highest + 1)));
    }

    /**
     * do_dig: the statuses the description groups as block actions must be exactly the statuses
     * that refuse to invent a target. The refusal is the only thing standing between a coordinate
     * the caller forgot and a dig at the world origin on a live server, and the description names
     * three statuses as taking a position -- so all three must be guarded, not the first one.
     */
    @Test
    public void digGuardsEveryStatusItsLegendCallsABlockAction() {
        ToolRegistry reg = unwired();
        List<String> refusedWithoutCoords = new ArrayList<>();
        for (C07PacketPlayerDigging.Action a : C07PacketPlayerDigging.Action.values()) {
            String reply = call(reg, "do_dig", Map.of("status", a.name()));
            if (!reachedSendPath(reply, "dig " + a.name())) {
                assertTrue("a refusal must name the target it will not invent, or the caller "
                        + "re-sends the same call instead of supplying coordinates: " + reply,
                        reply.contains("0,0,0"));
                refusedWithoutCoords.add(a.name());
            }
        }

        int split = tool("do_dig").description().indexOf("(mining a block at pos+face)");
        assertTrue("the description must still group the position-taking statuses before its "
                + "pos+face clause for this comparison to mean anything", split > 0);
        String blockClause = tool("do_dig").description().substring(0, split);
        for (C07PacketPlayerDigging.Action a : C07PacketPlayerDigging.Action.values()) {
            assertEquals("the legend and the guard disagree about " + a.name() + ": a status "
                    + "documented as acting on a block at pos+face, yet accepted with no pos, "
                    + "goes out as a dig at (0,0,0) -- the model is told 'sent' and starts mining "
                    + "the world origin instead of the block it meant",
                    blockClause.contains(a.name()), refusedWithoutCoords.contains(a.name()));
        }

        // The positive half: the guard is about a MISSING target, not about the status. Every
        // guarded status must still send once coordinates are supplied, or the tool is just broken.
        for (String blocking : refusedWithoutCoords) {
            String withCoords = call(reg, "do_dig",
                    Map.of("status", blocking, "x", 12, "y", 64, "z", -7));
            assertTrue("with a target supplied " + blocking + " must reach the wire: " + withCoords,
                    reachedSendPath(withCoords, "dig " + blocking));
        }
    }

    /**
     * disconnect_report at the TOOL layer. {@code DisconnectSensorHonestyTest} drives the capture
     * side and proves the tracker can tell a dead sensor from a real negative; nothing drove the
     * tool that has to ACT on that distinction, so the branch that does the acting was free to
     * point either way. Both directions are asserted here because each is a separate lie: a dead
     * sensor answering "no disconnect" invents an observation, and a live sensor answering
     * "unavailable" throws away a real one and teaches the model to distrust its instruments.
     */
    @Test
    public void disconnectReportClaimsNoDisconnectOnlyFromASensorThatWasLive() {
        String deadSensor = disconnectReport(false, false);
        assertTrue("a sensor that was never installed has observed nothing and knows nothing; "
                + "answering the question at all makes the model rule out a kick it never looked "
                + "for: " + deadSensor, deadSensor.startsWith("ERROR"));
        assertTrue("and it must say WHICH source is missing, or the operator cannot fix it",
                deadSensor.contains("-javaagent"));
        assertFalse("the authoritative negative must not appear when nothing was watching",
                deadSensor.contains("No disconnect observed yet"));

        String liveSensor = disconnectReport(true, false);
        assertTrue("a live sensor that saw nothing IS an answer -- refusing it here trains the "
                + "model to treat its own instrumentation as broken and to stop asking: "
                + liveSensor, liveSensor.startsWith("OK"));
        assertTrue("and the answer is the real negative", liveSensor.contains("No disconnect"));

        String observed = disconnectReport(false, true);
        assertTrue("a delivered disconnect proves the sensor was live whatever the probe says, so "
                + "the reason must be reported: " + observed, observed.startsWith("OK"));
        assertTrue("carrying the kick reason verbatim, which is the whole point of the tool",
                observed.contains("kicked: flying"));

        assertTrue("the tool must advertise the source it depends on, so the unavailable reply "
                + "reads as a missing sensor rather than as a clean bill of health",
                tool("disconnect_report").description().contains("-javaagent"));
    }

    /** Drive disconnect_report against a tracker in one of its three honest states. */
    private static String disconnectReport(boolean sensorLive, boolean sawDisconnect) {
        EventBus bus = new EventBus();
        DisconnectTracker tracker = new DisconnectTracker(bus, new PacketLog(64), () -> sensorLive);
        if (sawDisconnect) {
            bus.publish(new DisconnectedEvent(new ChatComponentText("kicked: flying")));
        }
        ToolRegistry reg = new ToolRegistry(new ToolContext(null, null, null, null, tracker));
        return call(reg, "disconnect_report", Map.of());
    }

    /**
     * do_click_slot: the two ints the schema names must arrive on the wire in the roles it names
     * them for. windowId is the container id the server matches against the window it has open;
     * carrying the slot index in that field means the packet fails that match and is dropped
     * outright -- the second silent drop the tool's own description warns about -- while the reply
     * still says the click was sent. The reply is checked against the PACKET rather than against
     * the arguments, because the label is built from the arguments and stays truthful-looking
     * whatever the constructor was handed.
     */
    @Test
    public void clickSlotSendsWindowIdAsTheWindowAndSlotIdAsTheSlot() throws Exception {
        CapturingWire wire = new CapturingWire();
        ToolRegistry reg = husk(wire);

        String reply = call(reg, "do_click_slot", Map.of("windowId", 7, "slotId", 13));
        assertTrue("the click must reach the wire for this to test anything: " + reply,
                reply.startsWith("OK") && reply.contains("sent "));
        C0EPacketClickWindow sent = (C0EPacketClickWindow) wire.last();
        assertEquals("windowId must be the CONTAINER id: with the slot index there the server's "
                + "openContainer check fails and the click does nothing at all, so a model reading "
                + "'sent' concludes the item moved and plans its next click on a state that never "
                + "changed", 7, sent.getWindowId());
        assertEquals("slotId must be the slot index, for the same reason from the other side",
                13, sent.getSlotId());
        assertTrue("the reply's win=/slot= must describe the packet that went out rather than echo "
                + "the request back, since the echo is what a caller checks: " + reply,
                reply.contains("click_slot win=" + sent.getWindowId()
                        + " slot=" + sent.getSlotId()));

        // The schema's "0 = own inventory" is the case the swap hides best: 0 is a plausible slot
        // index, so a swapped packet still looks well-formed to anyone reading only the reply.
        call(reg, "do_click_slot", Map.of("windowId", 0, "slotId", 5));
        C0EPacketClickWindow ownInventory = (C0EPacketClickWindow) wire.last();
        assertEquals("'0 = own inventory' must arrive as window 0",
                0, ownInventory.getWindowId());
        assertEquals("and slot 5 as the slot", 5, ownInventory.getSlotId());
    }

    /**
     * The shared reporting seam for all ten typed do_* tools. Its javadoc exists so that veto /
     * not-connected / success read identically from every one of them, and the veto is the branch
     * no test reached: {@code SendToolsW6Test} stops at validation early-returns and
     * {@code BoardTraceLinkTest} stops at the link layer. Reporting a vetoed packet as sent is the
     * worst outcome of the three -- the safety layer refused, and the model is told it happened.
     */
    @Test
    public void aPacketTheBoardVetoedIsReportedAsVetoedAndNeverAsSent() throws Exception {
        CapturingWire wire = new CapturingWire();
        ToolRegistry reg = husk(wire);
        vetoEveryPacketSend("chest clicks blocked while trading");

        String reply = call(reg, "do_click_slot", Map.of("windowId", 0, "slotId", 5));
        assertEquals("a vetoed packet never left the client, so nothing may be on the wire",
                0, wire.count());
        assertTrue("a veto must be an error: reported as success, the model reasons from an "
                + "inventory move that never happened and every later click is aimed at a state it "
                + "invented: " + reply, reply.startsWith("ERROR"));
        assertFalse("and it must not say 'sent' in any form -- that word is the model's evidence "
                + "the packet left", reply.contains("sent "));
        assertTrue("it must name itself a veto, so the model changes the POLICY it is fighting "
                + "rather than retrying the same click forever", reply.contains("vetoed"));
        assertTrue("carrying the chip's reason, which is the only clue to what to do instead",
                reply.contains("chest clicks blocked while trading"));
    }

    /**
     * world_view mode=diff: the baseline must be the CALLER'S previous view. Drop it and
     * {@code WorldViewDiff} takes its documented null-prev fallback and answers "full" forever --
     * every diff promise in that ~2 KB of description (entities.left, absence-means-unchanged,
     * effects gained/lost/expiring) becomes structurally unreachable, while each reply still looks
     * like a valid payload. The two calls are identical on purpose: only the presence of a baseline
     * separates them.
     */
    @Test
    public void worldViewDiffIsMeasuredAgainstTheCallersPreviousView() throws Exception {
        ToolRegistry reg = husk(new CapturingWire());
        // An unknown section name: every real section reads live world state, and the point here is
        // the baseline bookkeeping, not the contents.
        Map<String, Object> sameCall = Map.of("mode", "diff", "sections", List.of("none"));

        String first = call(reg, "world_view", sameCall);
        assertTrue("the FIRST diff has no baseline, and saying so is honest -- there is nothing to "
                + "diff against yet: " + first, first.contains("\"mode\":\"full\""));

        String second = call(reg, "world_view", sameCall);
        assertTrue("the second poll has a baseline (the view the first one returned) and must diff "
                + "against it; answering 'full' forever makes diff mode unreachable from MCP while "
                + "every reply still parses, so the model keeps re-reading unchanged state as news "
                + "and never learns what left: " + second, second.contains("\"mode\":\"diff\""));
        assertFalse("a diff-mode reply must not silently be a full projection wearing the diff "
                + "caller's request", second.contains("\"mode\":\"full\""));

        assertTrue("the legend must say the baseline is the caller's last call, since that is what "
                + "makes two identical polls mean different things",
                schemaProp(tool("world_view"), "mode").contains("since last world_view"));
    }

    // ---- the smallest client the send path will accept ----------------------

    private boolean huskInstalled;
    private boolean traceReplaced;
    private Object savedTrace;
    private Method savedTracePublish;
    private boolean savedTraceResolved;

    /**
     * Wire {@code GameAccess} through to {@code wire} with no game running.
     *
     * <p>{@code GameAccess} is final and resolves the connection statically
     * ({@code theMinecraft.thePlayer.sendQueue.netManager}), so the only way to observe what a
     * do_* tool actually puts on the wire is to make that field chain resolve. The links are
     * constructor-free husks: the vanilla constructors reach a window, a resource pack and a login
     * handshake, and none of that is needed here because nothing calls a husk METHOD -- the send
     * path only READS those four fields. {@link CapturingWire} is a real object, since it is the
     * one link whose behaviour matters.
     */
    private ToolRegistry husk(CapturingWire wire) throws Exception {
        Object handler = blank(NetHandlerPlayClient.class);
        set(handler, "netManager", wire);
        Object player = blank(EntityPlayerSP.class);
        set(player, "sendQueue", handler);
        Minecraft mc = (Minecraft) blank(Minecraft.class);
        // The dispatcher marshals onto the game thread and runs inline when it is already there;
        // claiming this thread is the game thread is what lets the send happen synchronously.
        set(mc, "mcThread", Thread.currentThread());
        set(mc, "thePlayer", player);
        set(mc, "theWorld", blank(WorldClient.class));
        setStatic(Minecraft.class, "theMinecraft", mc);
        huskInstalled = true;

        GameAccess game = new GameAccess();
        KeGameDispatcher exec = new KeGameDispatcher(mc);
        GameBridge.init(exec, game);
        return new ToolRegistry(
                new ToolContext(game, new ActionManager(game, exec), null, null, null));
    }

    /** A NetworkManager that reports an open channel and keeps whatever it is handed. */
    @SuppressWarnings("rawtypes")
    private static final class CapturingWire extends NetworkManager {

        private final List<Object> sent = new ArrayList<>();

        CapturingWire() {
            super(EnumPacketDirection.SERVERBOUND);
        }

        @Override
        public boolean isChannelOpen() {
            return true;
        }

        @Override
        public void sendPacket(Packet packetIn) {
            sent.add(packetIn);
        }

        Object last() {
            assertFalse("nothing reached the wire", sent.isEmpty());
            return sent.get(sent.size() - 1);
        }

        int count() {
            return sent.size();
        }
    }

    /**
     * Make the process-wide board link veto every packet send, the way a chip does. The link
     * resolves its trace once and caches it, so the injection goes into the shared instance
     * {@code ActionManager} reads and is undone in {@link #restoreProcessWideState}.
     */
    private void vetoEveryPacketSend(String reason) throws Exception {
        Trace trace = new Trace();
        trace.subscribe(PacketSendSignal.class, s -> s.cancel(reason));
        Method publish = null;
        for (Method m : Trace.class.getMethods()) {
            if (m.getName().equals("publish") && m.getParameterCount() == 1) {
                publish = m;
            }
        }
        assertTrue("board Trace.publish(Signal) must exist for a veto to be injectable",
                publish != null);

        BoardTraceLink link = BoardTraceLink.shared();
        savedTrace = get(link, "trace");
        savedTracePublish = (Method) get(link, "tracePublish");
        savedTraceResolved = (Boolean) get(link, "traceResolved");
        traceReplaced = true;
        set(link, "trace", trace);
        set(link, "tracePublish", publish);
        set(link, "traceResolved", Boolean.TRUE);
    }

    /** Both seams above are process-wide; leaving either installed would leak into other tests. */
    @After
    public void restoreProcessWideState() throws Exception {
        if (huskInstalled) {
            setStatic(Minecraft.class, "theMinecraft", null);
            GameBridge.init(null, null);
            huskInstalled = false;
        }
        if (traceReplaced) {
            BoardTraceLink link = BoardTraceLink.shared();
            set(link, "trace", savedTrace);
            set(link, "tracePublish", savedTracePublish);
            set(link, "traceResolved", savedTraceResolved);
            traceReplaced = false;
        }
    }

    /** Allocate without running a constructor. */
    private static Object blank(Class<?> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(theUnsafe.get(null), type);
    }

    /** Walks up the hierarchy: the husk fields are vanilla's and may be inherited or final. */
    private static void set(Object target, String field, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException inherited) {
                // declared further up; keep walking
            }
        }
        throw new NoSuchFieldException(field + " on " + target.getClass().getName());
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setStatic(Class<?> owner, String field, Object value) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }
}
