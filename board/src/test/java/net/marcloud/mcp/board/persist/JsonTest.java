package net.marcloud.mcp.board.persist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/** Regression tests for the hand-written {@link Json} codec. */
public class JsonTest {

    @Test
    public void writeThenParseIsStructurallyIdentical() {
        DataView root = new DataView();
        root.putString("s", "hi\n\"quoted\"\t");
        root.putLong("n", 42L);
        root.putDouble("d", 2.5);
        root.putBoolean("b", true);
        root.child("nested").putInt("inner", 7);

        String text = Json.write(root.raw());
        Map<String, Object> back = Json.parse(text);

        assertEquals("hi\n\"quoted\"\t", back.get("s"));
        assertEquals(Long.valueOf(42L), back.get("n"));
        assertEquals(Double.valueOf(2.5), back.get("d"));
        assertEquals(Boolean.TRUE, back.get("b"));
        assertTrue(back.get("nested") instanceof Map);
    }

    @Test
    public void integralNumbersReadBackAsLong() {
        Map<String, Object> m = Json.parse("{\"x\": 10, \"y\": -3}");
        assertEquals(Long.valueOf(10L), m.get("x"));
        assertEquals(Long.valueOf(-3L), m.get("y"));
    }

    @Test
    public void floatingNumbersReadBackAsDouble() {
        Map<String, Object> m = Json.parse("{\"x\": 1.5, \"y\": 2e3}");
        assertEquals(Double.valueOf(1.5), m.get("x"));
        assertEquals(Double.valueOf(2000.0), m.get("y"));
    }

    @Test
    public void nullAndArraysAreParsed() {
        Map<String, Object> m = Json.parse("{\"z\": null, \"list\": [1, \"two\", true]}");
        assertTrue(m.containsKey("z"));
        assertEquals(null, m.get("z"));
        assertTrue(m.get("list") instanceof List);
        List<?> list = (List<?>) m.get("list");
        assertEquals(3, list.size());
        assertEquals(Long.valueOf(1L), list.get(0));
        assertEquals("two", list.get(1));
        assertEquals(Boolean.TRUE, list.get(2));
    }

    @Test
    public void emptyObjectAndArray() {
        Map<String, Object> m = Json.parse("{\"o\": {}, \"a\": []}");
        assertTrue(((Map<?, ?>) m.get("o")).isEmpty());
        assertTrue(((List<?>) m.get("a")).isEmpty());
    }

    @Test
    public void unicodeEscapeRoundTrips() {
        Map<String, Object> m = Json.parse("{\"u\": \"\\u00e9\"}");
        assertEquals("é", m.get("u"));
    }

    @Test(expected = Json.JsonException.class)
    public void malformedThrows() {
        Json.parse("{ nope");
    }

    @Test(expected = Json.JsonException.class)
    public void trailingGarbageThrows() {
        Json.parse("{} trailing");
    }

    @Test(expected = Json.JsonException.class)
    public void nonObjectRootThrows() {
        Json.parse("[1, 2, 3]");
    }
}
