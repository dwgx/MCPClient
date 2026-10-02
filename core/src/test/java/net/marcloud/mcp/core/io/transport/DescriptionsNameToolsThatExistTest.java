package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import static org.junit.Assert.assertNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.modelcontextprotocol.spec.McpSchema.Tool;

import net.marcloud.mcp.core.cm.CmQuery;
import net.marcloud.mcp.core.cm.IntrospectionTools;
import net.marcloud.mcp.core.compat.Compat;
import net.marcloud.mcp.core.compat.CompatTools;
import net.marcloud.mcp.core.drivers.action.ActTools;
import net.marcloud.mcp.core.drivers.gui.GuiSnapshotService;
import net.marcloud.mcp.core.drivers.gui.GuiTools;
import net.marcloud.mcp.core.drivers.narrative.GoalStack;
import net.marcloud.mcp.core.drivers.narrative.NarrativeTools;
import net.marcloud.mcp.core.drivers.observe.ObserveTools;
import net.marcloud.mcp.core.drivers.store.MemoryStore;
import net.marcloud.mcp.core.drivers.store.MemoryTools;
import net.marcloud.mcp.core.drivers.video.DevTools;
import net.marcloud.mcp.core.flt.HookTools;
import net.marcloud.mcp.core.flt.seam.SeamTools;
import net.marcloud.mcp.core.io.Capability;
import net.marcloud.mcp.core.io.DynamicToolFactory;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.io.MetaTools;
import net.marcloud.mcp.core.kd.DebugTools;
import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.PacketJournal;
import net.marcloud.mcp.core.mm.MutateStateTools;
import net.marcloud.mcp.core.ps.PsSynthesizer;
import net.marcloud.mcp.core.ps.SynthTools;
import net.marcloud.mcp.core.se.AllowAllGate;
import net.marcloud.mcp.core.se.PermissionTools;
import net.marcloud.mcp.core.se.PrivilegeControlTools;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeToken;

import org.junit.Test;

/**
 * A tool description that names another tool is a CONTRACT, and a contract pointing at something
 * that does not exist is worse than no contract: it teaches the model a fluent falsehood, and the
 * model then reasons about the world from it.
 *
 * <p>Three such defects were each verified twice -- once by calling the tool on the live server,
 * once by reading the source -- and all three are pinned here:
 *
 * <ul>
 *   <li><b>do_select_slot</b> said to "read_player_state, or world_view's self section, and compare
 *       heldSlot". Neither carries it: {@code read_player_state} returns
 *       {@code name/pos/yaw/pitch/health/onGround}, and {@code world_view} with
 *       {@code sections:["self"]} returns {@code self} alone. The real field is
 *       {@code world_view sections:["inventory"] -> inventory.selectedSlot}. This lands on the one
 *       tool whose own reply says "**This reply is NOT confirmation**", so an agent following the
 *       pointer had no way at all to check whether the hotbar selection took effect -- and would
 *       assume it did.</li>
 *   <li><b>do_click_slot</b> said "Confirm with read_inventory", and no such tool exists among the
 *       81. It is the stated confirmation path for an action whose two failure modes are BOTH
 *       silent by protocol: the server-side window lock, and a windowId mismatch that no-ops. (Its
 *       own test pinned the phantom; see {@code ClickSlotDescriptionMatchesVanillaTest},
 *       rewritten.)</li>
 *   <li><b>packets_tail / packet_view</b> emitted "Install it via the seam netty-tap tool first" on
 *       the tap-absent path. There is no {@code netty-tap}; the real names are
 *       {@code seam_netty_install} / {@code seam_netty_uninstall}. Reproduced live: that was exactly
 *       the string handed back, on both tools.</li>
 * </ul>
 *
 * <p>Plus one context win, which is a defect and not a tradeoff: {@code ActTools.coord} wrote the
 * same description into THREE places -- the parent node and both {@code oneOf} branches -- so
 * {@code go_to}'s 298-char text and {@code walk_straight}'s 555-char text each shipped three times
 * over. A model reads that triplicate three times and learns nothing the first copy did not say.
 *
 * <p><b>Why the last test here is derived rather than hand-listed.</b> It walks every description
 * in the whole surface, pulls out every token shaped like a tool name, and asserts each one is a
 * real tool, a term the schemas themselves define, or a constant listed below with its reason. The
 * three defects above are then a category rather than three incidents: a description that invents
 * a sibling tool fails here with no test written for it. The non-tool vocabulary is DERIVED from
 * the schemas (property keys, enum values) rather than accumulated from scan failures, so the
 * allowlist cannot rot into "whatever the scan happens to trip on".
 */
public class DescriptionsNameToolsThatExistTest {

    /**
     * Lowercase snake_case constants a description may legitimately spell that are neither a tool
     * nor a schema term. Each entry carries the reason it is here; anything absent fails the
     * derived scan, which is exactly how {@code read_inventory} surfaced. Kept short on purpose --
     * a long list here would stop being evidence and start being a copy of the surface.
     */
    private static final Map<String, String> NOT_A_TOOL = Map.of(
            // Item and block registry names, used as worked examples in a legend.
            "diamond_pickaxe", "an example item illustrating wear arithmetic in world_view",
            "iron_ore", "an example block name in find_block's legend",
            "stone_slab", "an example item illustrating variant metadata in world_view",
            // debug_manage FOLDS the per-operation debug tools into one tool with an 'action'
            // argument, so it names them as ACTIONS rather than as callable names. They are real
            // top-level tools only when the L6 handle layer is wired, which makes them the worst
            // thing in this surface for a scan to guess at.
            "debug_handle", "a debug_manage ACTION, not a top-level tool",
            "debug_open_thread", "a debug_manage ACTION");

    /** snake_case -- how every tool name in this surface is spelled. */
    private static final Pattern TOOL_LIKE =
            Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+");

    // ===== the surface under test =====

    /**
     * The whole built-in surface, assembled exactly as {@code McpCore.start()} assembles it and as
     * {@code RegisteredBuiltinGateCoverageTest} assembles it headlessly: every collaborator that is
     * only touched from inside a handler left null, because registration never dereferences one.
     */
    private static synchronized List<Capability> surface() {
        if (HELD == null) {
            IoSupervisor exec = new IoSupervisor(4, 2000L);
            SeLocalMonitor engine = new SeLocalMonitor(
                    new SeClearancePolicy(Ring.R_MINUS_1, "tok"), SeToken.wideOpen());
            IoManager reg = new IoManager(exec, engine);
            AllowAllGate gate = new AllowAllGate();

            new ToolRegistry(new ToolContext(null, null, null, null, null)).registerAll(reg);
            new MetaTools(reg, reg, new DynamicToolFactory(null), null).registerAll(reg);
            new PermissionTools(engine, reg).registerAll(reg);
            new PrivilegeControlTools(engine).registerAll(reg);
            new MemoryTools(new MemoryStore(Path.of(
                    System.getProperty("java.io.tmpdir", "."),
                    "mcp-xref-memory-" + System.nanoTime() + ".json"))).registerAll(reg);
            new NarrativeTools(new GoalStack(16)).registerAll(reg);
            new IntrospectionTools(new CmQuery(
                    DescriptionsNameToolsThatExistTest.class.getClassLoader(), List.of()))
                    .registerAll(reg);
            new HookTools(null, gate).registerAll(reg);
            new MutateStateTools(null, (net.marcloud.mcp.core.GameAccess) null).registerAll(reg);
            new SynthTools(new PsSynthesizer()).registerAll(reg);
            new SeamTools(null).registerAll(reg);
            new GuiTools(null, new GuiSnapshotService()).registerAll(reg);
            new ObserveTools(GameClock.INSTANCE, null, new PacketJournal(16), null).registerAll(reg);
            new ActTools().registerAll(reg);
            new DebugTools(gate).registerAll(reg);
            new CompatTools(Compat.database(), Compat.engine()).registerAll(reg);
            new DevTools(null).registerAll(reg);
            HELD = reg.capabilities();
        }
        return HELD;
    }

    private static List<Capability> HELD;

    private static Capability cap(String name) {
        for (Capability c : surface()) {
            if (name.equals(c.name())) {
                return c;
            }
        }
        throw new AssertionError("tool not in the surface: " + name);
    }

    private static Tool tool(String name) {
        return cap(name).spec().tool();
    }

    private static String desc(String name) {
        return cap(name).description();
    }

    /** name -> description, as {@code tools/list} ships them. */
    private static Map<String, String> descriptions() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Capability c : surface()) {
            out.put(c.name(), String.valueOf(c.description()));
        }
        return out;
    }

    /** Every property key and enum value anywhere in the surface's schemas. */
    private static Set<String> schemaVocabulary() {
        Set<String> out = new TreeSet<>();
        for (Capability c : surface()) {
            collectSchemaVocabulary(c.spec().tool().inputSchema(), out);
        }
        return out;
    }

    private static void collectSchemaVocabulary(Object o, Set<String> out) {
        if (o instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if ("properties".equals(e.getKey()) && e.getValue() instanceof Map<?, ?> p) {
                    for (Object k : p.keySet()) {
                        out.add(String.valueOf(k));
                    }
                }
                if ("enum".equals(e.getKey()) && e.getValue() instanceof List<?> l) {
                    for (Object v : l) {
                        out.add(String.valueOf(v));
                        out.add(String.valueOf(v).toUpperCase(Locale.ROOT));
                    }
                }
                collectSchemaVocabulary(e.getValue(), out);
            }
        } else if (o instanceof List<?> l) {
            for (Object v : l) {
                collectSchemaVocabulary(v, out);
            }
        }
    }

    // ===== (a) do_select_slot points at a field that is not there =====

    /**
     * The held slot lives in the INVENTORY section only. Asserted positively (the description must
     * name the real path) and negatively (the two paths it used to name must be gone), because
     * leaving the old pointer alongside the new one is the same defect in a longer sentence.
     */
    @Test
    public void doSelectSlotPointsAtTheFieldThatActuallyCarriesTheHeldSlot() {
        String d = desc("do_select_slot");
        assertTrue("the confirmation must name the section that carries it: world_view "
                + "sections=['inventory'] -> inventory.selectedSlot. Neither read_player_state "
                + "nor world_view's self section has a held slot -- verified live, both replies "
                + "lack any such field, so the old pointer sent the reader nowhere.",
                d.contains("sections=['inventory']"));
        assertTrue("and name the field itself, or the reader is sent hunting for 'heldSlot' again",
                d.contains("selectedSlot"));
        assertFalse("'heldSlot' is not a key in any reply this surface produces", d.contains("heldSlot"));
    }

    /** The same wrong pointer, asserted over the WHOLE surface, so it cannot come back anywhere. */
    @Test
    public void noDescriptionSendsTheReaderToCompareHeldSlot() {
        for (var e : descriptions().entrySet()) {
            assertFalse(e.getKey() + " tells the reader to compare 'heldSlot', which no reply in "
                    + "this surface contains", e.getValue().contains("heldSlot"));
        }
    }

    // ===== (b) do_click_slot named a tool that does not exist =====

    /**
     * The positive half lives in {@code ClickSlotDescriptionMatchesVanillaTest}, rewritten (its old
     * assertion passed <em>because</em> the phantom was present). What is asserted here is the
     * premise both halves rest on: the surface really is missing the named tool, and really does
     * contain the tools that replaced it.
     */
    @Test
    public void theConfirmationPathsThisSurfaceNamesAllExist() {
        Set<String> real = new TreeSet<>();
        for (Capability c : surface()) {
            real.add(c.name());
        }
        assertFalse("read_inventory is not a tool in this surface; if it ever becomes one, every "
                + "confirmation pointer at it must be re-derived from what it actually returns",
                real.contains("read_inventory"));
        for (String required : List.of("world_view", "gui_snapshot", "packet_view",
                "seam_netty_install", "seam_netty_uninstall", "act_set", "act_status",
                "read_player_state")) {
            assertTrue(required + " is named as a confirmation or install path in this surface, "
                    + "so it had better exist", real.contains(required));
        }
    }

    /** The phantom, asserted absent from every description in the surface. */
    @Test
    public void noDescriptionNamesTheNonExistentReadInventory() {
        for (var e : descriptions().entrySet()) {
            assertFalse(e.getKey() + " points the reader at read_inventory, which is not a tool "
                    + "in this surface -- and it is named as the confirmation path for the one "
                    + "operation whose two failure modes are both silent",
                    e.getValue().contains("read_inventory"));
        }
    }

    // ===== (c) "the seam netty-tap tool" does not exist =====

    /**
     * The {@code [requires: ...]} tag has the same defect as the runtime string it accompanies: it
     * reads as a capability name and there is no such tool. Asserted over the whole surface, so the
     * phantom cannot reappear under a different tag spelling.
     */
    @Test
    public void noRequiresTagNamesTheNonExistentNettyTapTool() {
        for (var e : descriptions().entrySet()) {
            String d = e.getValue();
            int at = d.indexOf("[requires:");
            if (at < 0) {
                continue;
            }
            int close = d.indexOf(']', at);
            String tags = d.substring(at + "[requires:".length(), close < 0 ? d.length() : close);
            for (String raw : tags.split(",")) {
                assertFalse(e.getKey() + " requires '" + raw.trim() + "', which reads as the tool "
                        + "'netty-tap' -- and no such tool exists in this surface. The real names "
                        + "are seam_netty_install / seam_netty_uninstall.",
                        raw.contains("netty-tap"));
            }
        }
    }

    // ===== (d) one description emitted three times into one schema =====

    /**
     * The general form of the defect, asserted over every combinator node in every schema in the
     * surface rather than at go_to / walk_straight by name: <b>a node must not repeat its own
     * description onto its own {@code oneOf} branches</b>.
     *
     * <p>The scope is the defect's own shape, and it is deliberately narrow. What was wrong was one
     * sentence written three times to describe <em>one</em> value -- the property's node and the
     * two alternative shapes under it -- so a reader of that property read it three times and
     * learned nothing the first copy did not say. A string appearing once under
     * {@code move.go_to} and again under {@code look.block} is not that: those are two different
     * arguments, each resolved on its own, and collapsing them would mean editing unrelated
     * arguments to satisfy a count.
     */
    @Test
    public void noCombinatorNodeRepeatsItsOwnDescriptionOntoItsBranches() {
        List<String> offenders = new ArrayList<>();
        for (Capability c : surface()) {
            checkCombinators(c.name(), c.spec().tool().inputSchema(), offenders);
        }
        assertTrue("a schema node must not carry the same description string as its own oneOf "
                + "branches. The model reads that sentence once per copy and learns nothing the "
                + "first did not say; JSON Schema needs it on the node the property is declared "
                + "at:\n  " + String.join("\n  ", offenders), offenders.isEmpty());
    }

    /** Every node carrying {@code oneOf}, checked against the descriptions on its branches. */
    private static void checkCombinators(String tool, Object node, List<String> offenders) {
        if (node instanceof Map<?, ?> m) {
            String own = m.get("description") instanceof String s ? s : null;
            if (own != null && m.get("oneOf") instanceof List<?> branches) {
                for (Object b : branches) {
                    for (String d : directDescriptions(b)) {
                        if (own.equals(d)) {
                            offenders.add(tool + ": a oneOf branch repeats the node's own "
                                    + "description \"" + abbreviate(own) + "\"");
                        }
                    }
                }
            }
            for (Object v : m.values()) {
                checkCombinators(tool, v, offenders);
            }
        } else if (node instanceof List<?> l) {
            for (Object v : l) {
                checkCombinators(tool, v, offenders);
            }
        }
    }

    /**
     * Descriptions on a branch node itself and on its immediate sub-nodes -- the copies a
     * combinator actually introduces. Not the whole subtree: a branch that legitimately contains a
     * nested object with its own described properties has not repeated anything.
     */
    private static List<String> directDescriptions(Object branch) {
        List<String> out = new ArrayList<>();
        if (!(branch instanceof Map<?, ?> m)) {
            return out;
        }
        if (m.get("description") instanceof String s) {
            out.add(s);
        }
        for (Object key : List.of("items", "additionalProperties", "not")) {
            if (m.get(key) instanceof Map<?, ?> sub
                    && sub.get("description") instanceof String s) {
                out.add(s);
            }
        }
        if (m.get("properties") instanceof Map<?, ?> p) {
            for (Object v : p.values()) {
                if (v instanceof Map<?, ?> sub && sub.get("description") instanceof String s) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /**
     * The information half. Dropping the copies must not drop what they said, so the prose has to
     * survive intact -- on the node the property is declared at, which is the canonical place a
     * reader resolving that property is shown.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void theCoordinateProseSurvivesOnTheNodeThePropertyIsDeclaredAt() {
        Map<String, Object> props =
                (Map<String, Object>) tool("act_set").inputSchema().get("properties");
        Map<String, Object> move = (Map<String, Object>) props.get("move");
        Map<String, Object> moveProps = (Map<String, Object>) move.get("properties");
        Map<String, Object> goTo = (Map<String, Object>) moveProps.get("go_to");

        String parent = String.valueOf(goTo.get("description"));
        assertTrue("go_to's own prose must still be here in full -- the facts it carries are "
                + "load-bearing ('PLACED to cross gaps', 'pass 0 to forbid building', "
                + "'THIS IS THE ONE TO REACH FOR'). Removing the duplicate copies must not remove "
                + "the prose.",
                parent.contains("PLACED to cross gaps")
                        && parent.contains("pass 0 to forbid building")
                        && parent.contains("THIS IS THE ONE TO REACH FOR"));

        // The branches must still be able to say which shape each accepts -- the one thing the
        // parent cannot. They say it STRUCTURALLY, which cannot be misread and costs nothing: a
        // branch carrying even a short repeated label is a second copy of a string this surface
        // then emits once per coordinate, which is the same waste in miniature.
        List<?> branches = (List<?>) goTo.get("oneOf");
        assertNotNull("coord must still be a oneOf over both shapes", branches);
        assertEquals("coord must keep accepting BOTH the array and the object form", 2,
                branches.size());
        Map<String, Object> array = null;
        Map<String, Object> object = null;
        for (Object b : branches) {
            Map<String, Object> m = (Map<String, Object>) b;
            if ("array".equals(m.get("type"))) {
                array = m;
            } else if ("object".equals(m.get("type"))) {
                object = m;
            }
        }
        assertNotNull("one branch must be the documented array form [x, y, z]", array);
        assertNotNull("and one the object form {x, y, z}, which is what every other tool here "
                + "uses for a position", object);
        assertEquals("the array form is exactly three elements", 3,
                ((Number) array.get("minItems")).intValue());
        assertEquals("and not four", 3, ((Number) array.get("maxItems")).intValue());
        assertEquals("the object form requires all three axes", List.of("x", "y", "z"),
                object.get("required"));
        assertNotNull("and declares them", object.get("properties"));
        for (Object b : branches) {
            assertNull("a branch must not carry a copy of the parent's prose -- that duplication "
                    + "IS the defect being fixed, and the shape is already declared structurally",
                    ((Map<String, Object>) b).get("description"));
        }
    }

    // ===== derived: every tool name a description mentions must exist =====

    /**
     * Walks every description in the whole surface, extracts every token shaped like a tool name,
     * and asserts each is a real tool, a term the schemas themselves define, or a constant listed
     * with its reason. This is what turns the three defects above into a category: an invented
     * sibling tool fails here with no test written for it.
     *
     * <p>Scoped to the surface's actual tool-name convention: lowercase snake_case. SCREAMING_CASE
     * tokens are excluded deliberately. This surface uses them for a different thing -- capability
     * SIDs (CAP_NETWORK_SEND), privileges (SE_NET_RAW), and the UPPER_SNAKE status values a
     * handler's reply reports (gui_click_element's NOT_CONFIRMED / REFUSED_STALE). Those are
     * constants of a reply, not names of a tool, and every one of them is allowed to exist without
     * being callable. Folding them in here would mean maintaining a list of every status value in
     * the surface, which fails the first time a sibling adds one and says nothing about the defect
     * this guards.
     */
    @Test
    public void everyToolNameMentionedInADescriptionIsARealTool() {
        Set<String> real = new TreeSet<>();
        for (Capability c : surface()) {
            real.add(c.name());
        }
        Set<String> schemaVocab = schemaVocabulary();
        Set<String> unexplained = new LinkedHashSet<>();
        for (var e : descriptions().entrySet()) {
            for (String token : tokens(e.getValue())) {
                if (real.contains(token) || schemaVocab.contains(token)
                        || NOT_A_TOOL.containsKey(token)) {
                    continue;
                }
                unexplained.add(e.getKey() + " mentions '" + token
                        + "', which is neither a tool nor a term its schema defines");
            }
        }
        assertTrue("a description must never name a tool that does not exist:\n  "
                + String.join("\n  ", unexplained), unexplained.isEmpty());
    }

    // ===== helpers =====

    /**
     * Tokens shaped like a tool name in this surface: lowercase snake_case, at least two segments.
     * SCREAMING_CASE is filtered out -- see the scan test for why.
     */
    private static Set<String> tokens(String desc) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TOOL_LIKE.matcher(desc);
        while (m.find()) {
            String t = m.group();
            if (t.equals(t.toLowerCase(Locale.ROOT))) {
                out.add(t);
            }
        }
        return out;
    }

    private static String abbreviate(String s) {
        return s.length() <= 56 ? s : s.substring(0, 53) + "...";
    }

    private static void collectDescriptions(Object o, Map<String, Integer> counts) {
        if (o instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if ("description".equals(e.getKey()) && e.getValue() instanceof String s) {
                    counts.merge(s, 1, Integer::sum);
                } else {
                    collectDescriptions(e.getValue(), counts);
                }
            }
        } else if (o instanceof List<?> l) {
            for (Object v : l) {
                collectDescriptions(v, counts);
            }
        }
    }
}