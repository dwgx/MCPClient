package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;

import net.marcloud.mcp.core.drivers.world.SlotClickMode;
import org.junit.Test;

/**
 * The DROP verb's ROUTE, which is a separate claim from its behaviour: {@link DropControllerTest}
 * proves what the controller does, this proves it can be reached at all and that the constant it
 * sends is the vanilla one rather than a 4 somebody typed.
 *
 * <p><b>Why routing deserves a test of its own.</b> {@code ActIntentParser} is the single parser
 * behind both {@code act_set} and {@code act_plan}, and its own javadoc says a new interact kind
 * must land in both or nowhere. A {@code Kind.DROP} that no argument could build would be a
 * constant with a controller behind it -- exactly the state {@code SlotClickMode} was in before
 * this work: a vocabulary transcribed faithfully from vanilla with nothing driving it.
 */
public class DropReachabilityTest {

    private static Map<String, Object> drop(int slot) {
        Map<String, Object> m = new HashMap<>();
        m.put("kind", "drop");
        m.put("slot", slot);
        return m;
    }

    @Test
    public void theToolMapBuildsADropNamingTheSlot() {
        InteractIntent ii = ActIntentParser.parseInteract(drop(17));
        assertEquals(InteractIntent.Kind.DROP, ii.kind());
        assertEquals("the slot must survive parsing, or the verb is unreachable",
                17, ii.playerSlot());
        assertEquals(ActSlot.INTERACT, ii.slot());
    }

    @Test
    public void allThirtySixSlotsParse() {
        for (int slot = 0; slot < DropController.SLOT_COUNT; slot++) {
            assertEquals("slot " + slot + " must parse", slot,
                    ActIntentParser.parseInteract(drop(slot)).playerSlot());
        }
    }

    @Test
    public void aSlotOutsideTheThirtySixIsRefusedByName() {
        for (int slot : new int[] {-1, 36}) {
            try {
                ActIntentParser.parseInteract(drop(slot));
                fail("slot " + slot + " must be refused: it is not a player inventory slot");
            } catch (IllegalArgumentException expected) {
                assertTrue("the refusal must name the slot's own bounds, got: "
                        + expected.getMessage(),
                        expected.getMessage().contains("0-35"));
            }
        }
    }

    @Test
    public void aMissingSlotIsRefusedRatherThanDefaultingToOne() {
        try {
            ActIntentParser.parseInteract(Map.of("kind", "drop"));
            fail("a drop with no slot must be refused, not aimed at slot 0");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("'slot'"));
        }
    }

    @Test
    public void aBlockTargetOnADropIsRefusedRatherThanIgnored() {
        Map<String, Object> m = drop(3);
        m.put("block", new int[] {1, 2, 3});
        try {
            ActIntentParser.parseInteract(m);
            fail("a block on a drop names something the verb cannot click");
        } catch (IllegalArgumentException expected) {
            assertTrue("the refusal must name the supplied key, got: " + expected.getMessage(),
                    expected.getMessage().contains("block"));
        }
    }

    @Test
    public void theKindIsOfferedInTheRefusalForAnUnknownOne() {
        try {
            ActIntentParser.parseInteract(Map.of("kind", "toss"));
            fail("'toss' is not a verb");
        } catch (IllegalArgumentException expected) {
            assertTrue("a kind that is refused must be LISTED in the refusal, or the caller"
                    + " cannot find the one it wanted: " + expected.getMessage(),
                    expected.getMessage().contains("drop"));
        }
    }

    @Test
    public void theDroppedStackIsTheOneVanillaNames() {
        // The controller's actuator is handed the mode by the LIVE implementation, not here, so
        // this pins the constant it is bound to: mode 4 is Container.slotClick's drop-from-a-slot
        // branch and NOT the slotId == -999 throw-the-cursor branch of mode 0, which is a
        // different gesture with different arguments.
        assertEquals("mode 4 is drop-from-a-NAMED-slot", 4, SlotClickMode.DROP_SLOT);
        assertEquals("and the other drop is a different mode with NO_SLOT as its slot", 0,
                SlotClickMode.PICKUP);
        assertEquals(-999, SlotClickMode.NO_SLOT);
    }
}