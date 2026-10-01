package net.marcloud.mcp.core.io.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * A record must serialise to a JSON object, not to the text of its {@code toString}.
 *
 * <p>Found on a live client: {@code inspect_block} answered
 * {@code Report[x=208, y=64, z=189, block=minecraft:tallgrass, ...]} -- a Java toString, not JSON.
 * A caller that parses it gets a string instead of the fields the tool promised, and the failure
 * mode is the dangerous kind: the reply is non-empty and plausible, so a reader that only checks
 * "did I get a reply" passes.
 *
 * <p>Nothing else in the surface was bitten because every other tool builds its Map by hand. That
 * is exactly why this needs a test rather than a convention: the convention held everywhere the
 * tool was new and failed the first time a record was returned directly.
 */
public final class ARecordIsWrittenAsAnObjectNotItsToStringTest {

    record Report(int x, int y, String block, boolean solid, double dist, List<String> tags) {
    }

    @Test
    public void aRecordBecomesAnObjectWithItsComponentNames() {
        String json = Json.write(new Report(208, 64, "minecraft:tallgrass", false, 1.5,
                List.of("a", "b")));

        assertTrue("must parse as JSON, got: " + json, json.startsWith("{") && json.endsWith("}"));
        assertTrue("and not as a Java toString: " + json, !json.contains("Report["));
        for (String k : new String[] {"x", "y", "block", "solid", "dist", "tags"}) {
            assertTrue("component '" + k + "' is missing from " + json, json.contains("\"" + k + "\""));
        }
    }

    @Test
    public void valuesKeepTheirJsonTypes() {
        String json = Json.write(new Report(1, 2, "dirt", true, 0.5, List.of()));
        assertTrue("an int must not be quoted: " + json, json.contains("\"x\":1"));
        assertTrue("a boolean must not be quoted: " + json, json.contains("\"solid\":true"));
        assertTrue("a double keeps its point: " + json, json.contains("0.5"));
        assertTrue("a list stays a list: " + json, json.contains("\"tags\":[]"));
    }

    @Test
    public void aNestedRecordIsAlsoWritten() {
        record Outer(String name, Report inner) {
        }
        String json = Json.write(new Outer("cell", new Report(1, 2, "dirt", false, 0.0, List.of())));
        assertTrue("a record inside a record must not fall back to toString: " + json,
                !json.contains("Report[") && json.contains("\"inner\":{"));
    }

    @Test
    public void theShapesThatAlreadyWorkedStillWork() {
        assertEquals("a Map is untouched", "{\"a\":1}", Json.write(Map.of("a", 1)));
        assertEquals("a List is untouched", "[1,2]", Json.write(List.of(1, 2)));
        assertEquals("a plain String is still quoted", "\"x\"", Json.write("x"));
        assertEquals("null is still null", "null", Json.write(null));
    }
}
