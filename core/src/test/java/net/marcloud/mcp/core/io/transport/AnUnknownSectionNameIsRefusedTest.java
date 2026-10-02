package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import net.marcloud.mcp.core.drivers.world.WorldViewCapture;
import org.junit.Test;

/**
 * A misspelled {@code sections} entry must be an ERROR, not a silent omission.
 *
 * <p>{@code WorldViewCapture.want} was a bare {@code contains}, so {@code
 * sections:["grid","inventroy"]} sampled the grid, matched nothing for the typo, and answered a
 * perfectly successful view with no inventory in it. Worse in diff mode, where the missing section
 * is indistinguishable from one that did not change: the caller is told its kit is untouched
 * because it asked about the wrong field.
 *
 * <p>This is the tool boundary's half of the fix -- the refusal, its message, and the fact that the
 * vocabulary it names is the one the sampler gates on. The refusal happens before the game is
 * touched, which is why this test needs no client: the reply must name the offending word rather
 * than fail somewhere deeper.
 */
public final class AnUnknownSectionNameIsRefusedTest {

    private static ToolRegistry registry() {
        return new ToolRegistry(new ToolContext(null, null, null, null, null));
    }

    /** One handler call, flattened to the string a model would actually read. */
    private static String call(ToolRegistry reg, Map<String, Object> args) {
        for (SyncToolSpecification spec : reg.all()) {
            if (spec.tool().name().equals("world_view")) {
                CallToolResult r = spec.callHandler()
                        .apply(null, new CallToolRequest("world_view", args));
                String text = "";
                for (Content c : r.content()) {
                    if (c instanceof TextContent t) {
                        text = t.text();
                    }
                }
                return (Boolean.TRUE.equals(r.isError()) ? "ERROR " : "OK ") + text;
            }
        }
        throw new AssertionError("world_view not found in the registry");
    }

    @Test
    public void aMisspelledSectionIsRefusedAndNamed() {
        String reply = call(registry(), Map.of("sections", List.of("grid", "inventroy")));

        assertTrue("a name the tool does not have must be refused, not dropped: the reply used to "
                + "be a successful view with that section simply absent: " + reply,
                reply.startsWith("ERROR"));
        assertTrue("and it must name the offending word, or the caller has to guess which of its "
                + "entries was wrong: " + reply, reply.contains("inventroy"));
        assertTrue("while spelling out the names that ARE accepted, so the retry is informed: " + reply,
                reply.contains(String.join(", ", WorldViewCapture.SECTIONS)));
        assertFalse("and it must not have produced a view at all -- a payload beside the error is a "
                + "payload some caller will use: " + reply, reply.contains("\"present\""));
    }

    @Test
    public void everyNameTheVocabularyHoldsIsAccepted() {
        for (String section : WorldViewCapture.SECTIONS) {
            String reply = call(registry(), Map.of("sections", List.of(section)));
            assertFalse("'" + section + "' is one of the sections the tool advertises, so it must "
                    + "not be refused: " + reply, reply.contains("unknown section"));
        }
    }

    /** An empty list means "the default", not "nothing" -- and must not be read as a bad name. */
    @Test
    public void anEmptySectionListIsNotRefused() {
        assertFalse("omitting every name asks for the default view, and the sampler reads it that "
                        + "way; refusing it would make the empty list unusable",
                call(registry(), Map.of("sections", List.of())).contains("unknown section"));
    }

    /**
     * The vocabulary is one list, so the schema cannot offer a name the sampler does not gate on
     * (nor hide one it does).
     */
    @Test
    public void theSchemaOffersExactlyTheVocabularyTheSamplerGatesOn() {
        String schema = null;
        String description = null;
        for (SyncToolSpecification spec : registry().all()) {
            if (spec.tool().name().equals("world_view")) {
                description = spec.tool().description();
                Object props = ((Map<?, ?>) spec.tool().inputSchema()).get("properties");
                Object sections = ((Map<?, ?>) props).get("sections");
                schema = String.valueOf(((Map<?, ?>) sections).get("description"));
            }
        }
        assertTrue("world_view must still advertise 'sections'", schema != null && description != null);
        assertTrue("the schema must list the names the sampler accepts: " + schema,
                schema.contains(String.join(",", WorldViewCapture.SECTIONS)));
        assertTrue("and the description must name them too, since that is where a caller reads what "
                + "a subset costs: " + description,
                description.contains(String.join(",", WorldViewCapture.SECTIONS)));
        assertTrue("the legend must state that an unknown name is refused rather than ignored: "
                + description, description.contains("REFUSED with an error naming it"));
    }
}
