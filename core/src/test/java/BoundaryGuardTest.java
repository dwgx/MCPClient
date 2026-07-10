import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.registry.BoundaryGuard;
import org.junit.Test;

/** L7: deep-copy+freeze (TOCTOU capture) + lightweight JSON-schema validation. */
public class BoundaryGuardTest {

    // ---- deep freeze ----

    @Test
    public void deepFreezeMakesNestedStructureImmutable() {
        Map<String, Object> src = new HashMap<>();
        List<Object> inner = new ArrayList<>();
        inner.add("a");
        src.put("list", inner);
        src.put("nested", new HashMap<>(Map.of("k", "v")));

        Map<String, Object> frozen = BoundaryGuard.freezeArgs(src);

        // Mutating the ORIGINAL after freezing must not affect the snapshot.
        inner.add("b");
        assertEquals("snapshot list unchanged", 1, ((List<?>) frozen.get("list")).size());

        // The frozen structures reject mutation.
        try {
            ((List<Object>) frozen.get("list")).add("x");
            org.junit.Assert.fail("frozen list must be immutable");
        } catch (UnsupportedOperationException expected) {
            // good
        }
        try {
            ((Map<String, Object>) frozen.get("nested")).put("k2", "v2");
            org.junit.Assert.fail("frozen nested map must be immutable");
        } catch (UnsupportedOperationException expected) {
            // good
        }
    }

    @Test
    public void freezeNullOrEmptyYieldsEmptyMap() {
        assertTrue(BoundaryGuard.freezeArgs(null).isEmpty());
        assertTrue(BoundaryGuard.freezeArgs(Map.of()).isEmpty());
    }

    // ---- schema validation ----

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required);
    }

    @Test
    public void requiredMissingFails() {
        Map<String, Object> s = schema(
                Map.of("name", Map.of("type", "string")), List.of("name"));
        BoundaryGuard.Result r = BoundaryGuard.validate(s, Map.of());
        assertFalse(r.ok());
        assertNotNull(r.message());
        assertTrue(r.message().contains("name"));
    }

    @Test
    public void wrongTypeFails() {
        Map<String, Object> s = schema(
                Map.of("count", Map.of("type", "integer")), List.of("count"));
        BoundaryGuard.Result r = BoundaryGuard.validate(s, Map.of("count", "not-a-number"));
        assertFalse(r.ok());
        assertTrue(r.message().contains("integer"));
    }

    @Test
    public void integerAcceptsWholeValuedDouble() {
        // JSON often decodes 5 as Double 5.0 — must be accepted as integer.
        Map<String, Object> s = schema(
                Map.of("count", Map.of("type", "integer")), List.of("count"));
        assertTrue(BoundaryGuard.validate(s, Map.of("count", 5.0)).ok());
        assertFalse(BoundaryGuard.validate(s, Map.of("count", 5.5)).ok());
    }

    @Test
    public void unknownPropertiesAllowed() {
        // additionalProperties defaults true — lets AI-authored empty-schema tools
        // accept any arg map.
        Map<String, Object> s = schema(Map.of(), List.of());
        assertTrue(BoundaryGuard.validate(s, Map.of("anything", "goes")).ok());
    }

    @Test
    public void enumEnforced() {
        Map<String, Object> s = schema(
                Map.of("target", Map.of("type", "string", "enum", List.of("R2", "R3"))),
                List.of("target"));
        assertTrue(BoundaryGuard.validate(s, Map.of("target", "R2")).ok());
        assertFalse(BoundaryGuard.validate(s, Map.of("target", "R9")).ok());
    }

    @Test
    public void nullSchemaIsPermissive() {
        assertTrue(BoundaryGuard.validate(null, Map.of("x", 1)).ok());
    }
}
