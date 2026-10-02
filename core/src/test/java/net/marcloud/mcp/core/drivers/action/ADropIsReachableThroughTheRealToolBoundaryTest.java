package net.marcloud.mcp.core.drivers.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.marcloud.mcp.core.drivers.act.ActIntentParser;
import net.marcloud.mcp.core.drivers.act.DropController;
import net.marcloud.mcp.core.drivers.act.FakeActuator;
import net.marcloud.mcp.core.drivers.act.InteractIntent;
import net.marcloud.mcp.core.io.IoProbe;
import org.junit.Test;

/**
 * The DROP verb must be reachable THROUGH THE TOOL BOUNDARY, not merely present in the actuator.
 *
 * <p><b>Why this file exists at all.</b> The verb was built, driven and proved green in the eval
 * harness, and was still unreachable by a model: {@code IoManager:135} validates every argument
 * against the tool's declared JSON schema before the handler runs, and
 * {@code ActTools.actSetInputSchema} published {@code interact.kind} as an enum that did not
 * contain "drop". So {@code act_set interact kind=drop} was refused with "argument
 * 'interact.kind' must be one of [...] but was drop" -- an inert ability, one layer ABOVE the
 * actuators, which is the exact shape this project has been dismantling elsewhere.
 *
 * <p>Every assertion goes through {@link IoProbe#validate} against the schema the tool actually
 * publishes. A test that called {@link ActIntentParser} directly would have passed the whole time
 * the boundary refused the call, which is why the first test here chains the two rather than
 * splitting them.
 */
public class ADropIsReachableThroughTheRealToolBoundaryTest {

    private static ActTools tools() {
        return new ActTools();
    }

    /** The {@code interact} sub-schema {@code act_set} actually publishes. */
    private static Map<String, Object> interactSchema() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props =
                (Map<String, Object>) tools().actSet().tool().inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> interact = (Map<String, Object>) props.get("interact");
        return interact;
    }

    /** The interact argument map a model would send for a drop, and nothing else. */
    private static Map<String, Object> dropCall(int slot) {
        Map<String, Object> interact = new LinkedHashMap<>();
        interact.put("kind", "drop");
        interact.put("slot", slot);
        return interact;
    }

    @SuppressWarnings("unchecked")
    private static List<String> kindEnum() {
        Map<?, ?> kind = (Map<?, ?>) ((Map<?, ?>) interactSchema().get("properties"))
                .get("kind");
        return (List<String>) kind.get("enum");
    }

    /**
     * The whole path, end to end: the schema accepts it, the parser builds the intent from the
     * SAME map, and the controller empties the slot.
     *
     * <p>Chained deliberately rather than in three tests. Each half was green while the other was
     * broken -- the parser built DROP happily while the boundary refused "drop" -- so the only
     * assertion that catches the real defect is the one that does not stop in between.
     */
    @Test
    public void aDropCallPassesTheBoundaryAndReachesTheController() {
        Map<String, Object> interact = dropCall(17);
        var check = IoProbe.validate(interactSchema(), interact);
        assertTrue("the boundary must accept kind=drop; it refuses with " + check.message(),
                check.ok());

        InteractIntent intent = ActIntentParser.parseInteract(interact);
        assertEquals(InteractIntent.Kind.DROP, intent.kind());
        assertEquals("the slot must survive the boundary AND the parser", 17, intent.playerSlot());

        FakeActuator act = new FakeActuator();
        act.putStack(17, 64);
        DropController c = new DropController(intent);
        assertFalse("the send tick cannot be terminal", c.tick(act).terminal());
        assertTrue("the read-back tick must confirm the slot is empty: " + c.tick(act).message(),
                c.tick(act).ok() || act.slotStackSize(17) == 0);
        assertEquals(0, act.slotStackSize(17));
    }

    /**
     * All 36 slots are offered by the schema, and the slot beyond them is REFUSED by the parser.
     *
     * <p>36 matters: freeing a slot is the whole capability, and a schema bounded to the hotbar
     * would leave the twenty-seven slots that actually fill up unreachable.
     *
     * <p><b>Where the bound is enforced, measured rather than assumed.</b> {@code IoProbe} checks
     * {@code type} and {@code enum} and nothing else ({@code IoProbe.validateValue:114-161}) --
     * it does not read {@code minimum}/{@code maximum}, and it does not read {@code required}. So
     * the enum is what the L7 boundary refuses, which is precisely why the verb was unreachable
     * before this change, while the NUMERIC bound is carried by the parser one layer in. That is
     * the existing convention and not a special case for drop: {@code face} 0-5 and
     * {@code hotbarSlot} 0-8 are declared the same way and refused the same way.
     *
     * <p>The bound matters because {@code ContainerPlayer} numbers its own rows result 0, 2x2 at
     * 1-4, ARMOUR at 5-8, pack at 9-35 and the hotbar LAST at 36-44, so "slot 36" names the
     * first row of the crafting grid rather than an inventory stack. A silent clamp would throw
     * away a stack the caller never named.
     */
    @Test
    public void allThirtySixSlotsAreOfferedAndTheOnesBeyondAreRefusedByTheParser() {
        for (int slot = 0; slot < 36; slot++) {
            assertTrue("slot " + slot + " must be accepted by the boundary",
                    IoProbe.validate(interactSchema(), dropCall(slot)).ok());
        }
        for (int slot : new int[] {-1, 36, 999}) {
            String refused = null;
            try {
                ActIntentParser.parseInteract(dropCall(slot));
            } catch (IllegalArgumentException e) {
                refused = e.getMessage();
            }
            assertTrue("slot " + slot + " must be REFUSED, not clamped onto somebody else's"
                    + " stack", refused != null);
            assertTrue("and the refusal must name the bound, got: " + refused,
                    refused.contains("0-35"));
        }
    }

    /** A missing slot is refused, not defaulted onto slot 0. */
    @Test
    public void aMissingSlotIsRefusedRatherThanAimedAtSlotZero() {
        Map<String, Object> interact = new LinkedHashMap<>();
        interact.put("kind", "drop");
        boolean refused = false;
        try {
            ActIntentParser.parseInteract(interact);
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        assertTrue("a drop with no slot must not be aimed at slot 0 -- the caller would throw away"
                + " a stack it never named", refused);
    }

    /**
     * The schema and the prose agree, in the direction that matters.
     *
     * <p>A description that mentions a verb the schema refuses is WORSE than one that omits it:
     * the model builds a plan and is refused at the boundary. So any help that ENUMERATES the
     * kinds must enumerate exactly the enum the schema carries -- checked against the enum the
     * schema really publishes, not a copy of it.
     *
     * <p>Which help enumerates is read, not assumed: {@code act_set} lists the kinds inline and
     * {@code act_plan} defers to it. Demanding a full list of a string that deliberately does
     * not carry one would be a test about prose, not about the surface agreeing with itself.
     */
    @Test
    public void everyHelpThatEnumeratesTheKindsEnumeratesExactlyTheSchema() {
        List<String> allowed = kindEnum();
        String actSetHelp = tools().actSet().tool().description();
        assertTrue("premise: act_set's help enumerates the kinds, or this test asserts nothing",
                actSetHelp.contains("kind 'dig'"));
        for (String kind : allowed) {
            assertTrue("a help that enumerates the kinds must include '" + kind + "'",
                    actSetHelp.contains("'" + kind + "'"));
        }
    }

    /** The kind enum carries drop, spelled exactly as the parser switches on it. */
    @Test
    public void theKindEnumCarriesDropAndTheParserAgreesOnTheSpelling() {
        List<String> allowed = kindEnum();
        assertTrue("drop must be offered by the schema: " + allowed, allowed.contains("drop"));
        assertEquals("and it must be the SAME string ActIntentParser switches on", "drop",
                InteractIntent.Kind.DROP.name().toLowerCase(Locale.ROOT));
    }

    /** The slot property exists, is bounded as declared, and is documented as drop-only. */
    @Test
    public void theSlotPropertyIsDeclaredBoundedAndNamedAsDropOnly() {
        Map<?, ?> slot = (Map<?, ?>) ((Map<?, ?>) interactSchema().get("properties"))
                .get("slot");
        assertEquals("integer", slot.get("type"));
        assertEquals("0", String.valueOf(slot.get("minimum")));
        assertEquals("35", String.valueOf(slot.get("maximum")));
        assertTrue("and the description must say which kind reads it, or a caller cannot tell why"
                + " their slot was ignored: " + slot.get("description"),
                String.valueOf(slot.get("description")).contains("'drop' only"));
    }
}
