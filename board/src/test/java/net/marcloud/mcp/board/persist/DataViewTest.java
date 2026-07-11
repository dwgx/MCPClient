package net.marcloud.mcp.board.persist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression tests for {@link DataView}'s tolerant, coercing accessors. */
public class DataViewTest {

    @Test
    public void missingKeysReturnDefaults() {
        DataView v = new DataView();
        assertEquals("d", v.getString("nope", "d"));
        assertEquals(5, v.getInt("nope", 5));
        assertEquals(9L, v.getLong("nope", 9L));
        assertEquals(1.5, v.getDouble("nope", 1.5), 0.0);
        assertTrue(v.getBoolean("nope", true));
        assertFalse(v.has("nope"));
    }

    @Test
    public void numericStringsCoerceToNumbers() {
        DataView v = new DataView();
        v.putString("n", "42");
        v.putString("d", "3.14");
        assertEquals(42, v.getInt("n", 0));
        assertEquals(42L, v.getLong("n", 0L));
        assertEquals(3.14, v.getDouble("d", 0.0), 0.0);
    }

    @Test
    public void nonNumericStringYieldsDefaultNotThrow() {
        DataView v = new DataView();
        v.putString("x", "not a number");
        assertEquals(7, v.getInt("x", 7));
        assertEquals(2.0, v.getDouble("x", 2.0), 0.0);
    }

    @Test
    public void booleanCoercionFromStringAndNumber() {
        DataView v = new DataView();
        v.putString("t", "TRUE");
        v.putString("f", "false");
        v.putInt("one", 1);
        v.putInt("zero", 0);
        assertTrue(v.getBoolean("t", false));
        assertFalse(v.getBoolean("f", true));
        assertTrue(v.getBoolean("one", false));
        assertFalse(v.getBoolean("zero", true));
        assertTrue("garbage boolean defaults", v.getBoolean("missing", true));
    }

    @Test
    public void wrongTypeYieldsDefault() {
        DataView v = new DataView();
        v.child("obj").putInt("k", 1);
        // reading a nested object as a scalar must default, not blow up
        assertEquals(3, v.getInt("obj", 3));
    }

    @Test
    public void getViewOnMissingKeyIsDetachedAndDoesNotMutate() {
        DataView v = new DataView();
        DataView missing = v.getView("absent");
        missing.putInt("k", 1);
        assertFalse("getView must not add the key to the parent", v.has("absent"));
        assertTrue(missing.has("k"));
    }

    @Test
    public void childCreatesAndReusesSameNested() {
        DataView v = new DataView();
        v.child("c").putInt("a", 1);
        v.child("c").putInt("b", 2); // must reuse, not clobber
        DataView c = v.getView("c");
        assertEquals(1, c.getInt("a", -1));
        assertEquals(2, c.getInt("b", -1));
    }

    @Test
    public void insertionOrderKeysPreserved() {
        DataView v = new DataView();
        v.putInt("z", 1);
        v.putInt("a", 2);
        v.putInt("m", 3);
        assertEquals("[z, a, m]", v.keys().toString());
    }
}
