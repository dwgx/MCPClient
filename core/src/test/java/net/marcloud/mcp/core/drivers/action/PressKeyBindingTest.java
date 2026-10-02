package net.marcloud.mcp.core.drivers.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The key mapper must refuse rather than guess.
 *
 * <p>This tool exists so the agent can open its own inventory. A wrong-but-successful mapping is
 * worse than no tool at all: pressing the wrong key opens a menu nobody asked for, and the run
 * record then shows a confirmed call that did something unintended. A refusal produces a rejected
 * step, which is the honest shape, so refusal is the behaviour worth pinning.
 *
 * <p>The numeric escape hatch is what keeps refusal usable — without it, an unnamed key would be
 * unreachable and the operator would have no way forward.
 */
public class PressKeyBindingTest {

    /**
     * Letters and digits must resolve to the SAME value the shim's constant holds.
     *
     * <p>These assertions compare against {@code Keyboard.KEY_*} rather than against literals, and
     * that is the whole point. The first version of the mapper computed {@code 48 + (c - 'a')}
     * and the first version of these tests asserted the same numbers, so the two agreed with each
     * other and both were wrong: {@code lwjgl2-shim} assigns USB HID usage codes, where KEY_A is
     * 0x1E and KEY_Z is 0x2C -- fifteen apart, not twenty-five. A test that shares the code's
     * assumption cannot catch the code's assumption.
     */
    @Test
    public void singleLettersAndDigitsResolveToTheShimConstants() {
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_A),
                ActTools.keyBindingCode("a"));
        assertEquals("case must not matter", Integer.valueOf(org.lwjgl.input.Keyboard.KEY_A),
                ActTools.keyBindingCode("A"));
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_Z),
                ActTools.keyBindingCode("z"));
        assertEquals("the inventory key is the whole point of this tool",
                Integer.valueOf(org.lwjgl.input.Keyboard.KEY_E),
                ActTools.keyBindingCode("e"));
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_0),
                ActTools.keyBindingCode("0"));
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_9),
                ActTools.keyBindingCode("9"));
    }

    /**
     * Every letter must map to a DISTINCT code, and to the constant that names it.
     *
     * <p>This is the check that would have caught the offset arithmetic on its own, without
     * needing anyone to know what KEY_A happens to be: if the mapper computed
     * {@code 48 + (c - 'a')} while the shim uses HID usage codes, then some letters collided
     * with other keys' constants, and comparing each result against its own constant fails for
     * exactly the letters whose numeric guess was wrong.
     */
    @Test
    public void everyLetterMapsToItsOwnDistinctCode() {
        java.util.Map<Integer, String> seen = new java.util.LinkedHashMap<>();
        for (char c = 'a'; c <= 'z'; c++) {
            Integer code = ActTools.keyBindingCode(String.valueOf(c));
            assertNotNull("letter " + c + " must resolve", code);
            String clash = seen.put(code, String.valueOf(c));
            assertNull("letter " + c + " collides with " + clash + " on code " + code
                    + " -- the mapper is computing codes instead of reading them", clash);
        }
        assertEquals("all 26 letters must resolve", 26, seen.size());
    }

    @Test
    public void namedKeysMapToTheirLwjglCodes() {
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_ESCAPE),
                ActTools.keyBindingCode("Escape"));
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_ESCAPE),
                ActTools.keyBindingCode("esc"));
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_RETURN),
                ActTools.keyBindingCode("Return"));
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_RETURN),
                ActTools.keyBindingCode("enter"));
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_TAB),
                ActTools.keyBindingCode("tab"));
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_SPACE),
                ActTools.keyBindingCode("space"));
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_BACK),
                ActTools.keyBindingCode("backspace"));
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_LSHIFT),
                ActTools.keyBindingCode("shift"));
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_F1),
                ActTools.keyBindingCode("F1"));
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_F12),
                ActTools.keyBindingCode("f12"));
        assertEquals(Integer.valueOf(org.lwjgl.input.Keyboard.KEY_UP),
                ActTools.keyBindingCode("Up"));
    }

    @Test
    public void anUnknownNameIsRefusedNotGuessed() {
        assertNull("an unrecognised name must resolve to nothing, not to a nearby key",
                ActTools.keyBindingCode("inventory"));
        assertNull(ActTools.keyBindingCode("F13"));
        assertNull(ActTools.keyBindingCode(""));
        assertNull(ActTools.keyBindingCode("   "));
        assertNull("a nonsense string is not a key", ActTools.keyBindingCode("!!!"));
    }

    /**
     * A name the shim knows must win over the numeric reading of the same text.
     *
     * <p>This is the defect a test caught: the numeric escape hatch ran first, so {@code "0"}
     * parsed as the integer 0 and returned KEY_NONE. Pressing a digit row then did nothing at
     * all, and the run record could not tell that from a key that was simply unbound -- the reply
     * for both was a success with an unremarkable code.
     */
    @Test
    public void aKnownNameBeatsTheNumericReadingOfTheSameText() {
        assertEquals("\"0\" must be the digit row, not the number zero",
                Integer.valueOf(org.lwjgl.input.Keyboard.KEY_0),
                ActTools.keyBindingCode("0"));
        assertNotNull("and it must not be KEY_NONE", ActTools.keyBindingCode("0"));
        assertTrue("KEY_NONE is 0, which is exactly what the bug produced",
                ActTools.keyBindingCode("0").intValue() != org.lwjgl.input.Keyboard.KEY_NONE
                        || org.lwjgl.input.Keyboard.KEY_0 != org.lwjgl.input.Keyboard.KEY_NONE);
    }

    @Test
    public void aNumericCodeIsTheEscapeHatchAndIsRangeChecked() {
        assertEquals("the numeric form must pass through unchanged",
                Integer.valueOf(18), ActTools.keyBindingCode("18"));
        assertEquals(Integer.valueOf(256), ActTools.keyBindingCode("256"));
        assertNull("a negative code is not a key", ActTools.keyBindingCode("-1"));
        assertNull("an out-of-range code is not a key",
                ActTools.keyBindingCode("999999"));
    }

    /** The description must not overstate what the tool does. */
    @Test
    public void theDescriptionSaysNoLwjglEventIsSynthesised() {
        var spec = new ActTools(null).pressKeyBinding();
        String d = spec.tool().description();
        assertTrue("the reply contract must state that no hardware event exists, so nothing "
                        + "downstream can claim a key hook saw this",
                d.contains("NO LWJGL EVENT IS SYNTHESISED"));
        assertTrue("and it must insist on the RELEASE pairing, because a binding left pressed is "
                + "a player walking into a wall forever", d.contains("PAIR EVERY PRESS"));
        assertTrue("and it must name the two vanilla calls it issues, so the claim is checkable",
                d.contains("setKeyBindState") && d.contains("onTick"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theSchemaRequiresTheKeyAndEnumeralisesThePhase() {
        var schema = new ActTools(null).pressKeyBinding().tool().inputSchema();
        assertNotNull("the tool must declare a schema", schema);
        Object required = schema.get("required");
        assertTrue("key must be the required argument, found: " + required,
                required instanceof List<?> r && r.contains("key"));
        Object phase = ((Map<String, Object>) schema.get("properties")).get("phase");
        assertNotNull("phase must exist", phase);
        Object en = ((Map<String, Object>) phase).get("enum");
        assertTrue("phase must be a closed enum, not a free string -- the first version of this "
                        + "tool had no enum here and a typo would silently mean something else",
                en instanceof List<?> l && l.contains("PRESS") && l.contains("RELEASE")
                        && l.size() == 2);
    }
}
