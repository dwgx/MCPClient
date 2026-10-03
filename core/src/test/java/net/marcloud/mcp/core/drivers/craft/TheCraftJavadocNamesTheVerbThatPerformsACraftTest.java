package net.marcloud.mcp.core.drivers.craft;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import net.marcloud.mcp.core.McpCore;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.flt.FltDynamicManager;
import net.marcloud.mcp.core.flt.FltManager;
import net.marcloud.mcp.core.flt.seam.SeamController;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoProbe;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ldr.LdrEngine;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeToken;

import org.junit.Test;

/**
 * {@code Craft}'s own javadoc used to say, in the file that is the craft package's public face:
 *
 * <blockquote>
 * "What this deliberately does NOT do is craft anything. Executing a craft needs a live
 * {@code CraftWindow} over the open container, and no implementation of that interface exists outside
 * the tests yet."
 * </blockquote>
 *
 * <p>That sentence was true when written, and then the file it described moved. {@code act_set}
 * {@code interact kind='craft'} shipped, {@link LiveCraftWindow} shipped, and the sentence did not
 * -- so a document that had been honest became a lie <em>without one byte of it changing</em>.
 *
 * <p><b>Why this is its own shape and not a fifteenth copy of §2.6.</b> The other fourteen are "the
 * capability is finished and nobody wired it": the producer is absent and the document is innocent.
 * Here the wiring was <em>successful</em>, and that success is what falsified the document. Nothing
 * goes red when a correct fix lands -- a green suite is the <em>cause</em> of this shape rather than
 * an obstacle to it, which is why no test written before the fix could have caught it.
 *
 * <p><b>Why the consumer makes it worse than any of the others.</b> The internal instances are read
 * by a maintainer who can open the next file. This one was read by whoever writes a prompt, and a
 * prompt author who believes the surface cannot craft will not write the three lines that make
 * crafting happen. That is also why the ruling below is asked of the <em>driven registry</em> and
 * not of a list of phrases: a forbidden-phrase list copied into this test would be a second copy of
 * the sentence, and the second copy is the thing that rots.
 */
public final class TheCraftJavadocNamesTheVerbThatPerformsACraftTest {

    /** snake_case -- how every tool name in this surface is spelled. */
    private static final Pattern TOOL_LIKE =
            Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+");

    /** Surefire's working directory is the module directory; the repo root is the fallback. */
    private static String craftSource() {
        Path[] candidates = {
            Path.of("src/main/java/net/marcloud/mcp/core/drivers/craft/Craft.java"),
            Path.of("core/src/main/java/net/marcloud/mcp/core/drivers/craft/Craft.java"),
        };
        for (Path at : candidates) {
            if (Files.isRegularFile(at)) {
                try {
                    return Files.readString(at);
                } catch (IOException e) {
                    throw new AssertionError("could not read " + at.toAbsolutePath(), e);
                }
            }
        }
        // A pin that quietly stops checking is worse than no pin. FAILS rather than skipping.
        throw new AssertionError("Craft.java is at neither " + candidates[0].toAbsolutePath()
                + " nor " + candidates[1].toAbsolutePath());
    }

    /**
     * The class javadoc only -- the block immediately above {@code public final class Craft}. A scan
     * over the whole file would also read method javadocs; this ruling is about the paragraph a
     * reader meets FIRST, because that is the one that says what the package can do.
     */
    private static String craftClassJavadoc() {
        String source = craftSource();
        int decl = source.indexOf("public final class Craft");
        assertTrue("the declaration moved and this pin no longer identifies the javadoc above it "
                + "-- repair the test, do not delete it", decl > 0);
        int open = source.lastIndexOf("/**", decl);
        int close = source.lastIndexOf("*/", decl);
        assertTrue("no javadoc block immediately above the declaration", open >= 0 && close > open);
        return source.substring(open, close);
    }

    /** Every tool-shaped name the class javadoc spells, in order, deduplicated. */
    private static Set<String> toolsNamedInJavadoc() {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TOOL_LIKE.matcher(craftClassJavadoc());
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Drive production's own {@code registerBuiltins} -- the single registration site. */
    private static Driven drive() {
        SeLocalMonitor engine = new SeLocalMonitor(
                new SeClearancePolicy(Ring.R_MINUS_1, "tok"), SeToken.wideOpen());
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        IoManager audited = new IoManager(exec, engine);
        EventBus bus = new EventBus();
        McpCore core = new McpCore();
        core.registerBuiltins(audited, engine, null,
                new ToolContext(null, null, null, null, null),
                new LdrEngine(TheCraftJavadocNamesTheVerbThatPerformsACraftTest.class.getClassLoader()),
                new FltManager(bus), new FltDynamicManager(null, bus),
                new SeamController(bus, core.gameAccess()),
                ActRuntime.INSTANCE);
        return new Driven(core.modelSurface(), exec);
    }

    private record Driven(IoManager surface, IoSupervisor exec) {
    }

    /**
     * The premise, asserted BEFORE anything is ruled about the javadoc: a craft really is
     * performable through the boundary a model calls, and a shipped {@link CraftWindow} really
     * exists. If this goes red, the naming check below would be ruling about a world that no longer
     * exists, so the failure names that rather than letting a stale document pass as honest.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void theRealBoundaryStillPerformsACraft() {
        assertTrue("LiveCraftWindow is expected to be the shipped CraftWindow implementation; if it "
                + "is gone, this file's subject has changed and it must be re-reasoned, not left "
                + "passing", CraftWindow.class.isAssignableFrom(LiveCraftWindow.class));

        Driven d = drive();
        try {
            Map<String, Object> interact = null;
            for (SyncToolSpecification spec : d.surface().currentSpecs()) {
                if ("act_set".equals(spec.tool().name())) {
                    interact = (Map<String, Object>) ((Map<String, Object>)
                            spec.tool().inputSchema().get("properties")).get("interact");
                }
            }
            assertTrue("act_set must be on the model surface for this file to have a subject",
                    interact != null);

            var check = IoProbe.validate(interact, Map.of("kind", "craft", "item", "minecraft:stick"));
            assertTrue("the model-facing boundary must accept kind='craft'; it refuses with "
                    + check.message(), check.ok());
        } finally {
            d.exec().shutdown();
        }
    }

    /**
     * The ruling. Derived from the driven surface, not from a list of phrases copied out of the
     * sentence being policed: for every tool-shaped name the javadoc spells, ask the surface whether
     * THAT tool's published schema accepts a craft.
     *
     * <p>Restoring the old sentence turns this red with no edit here, because the old javadoc named
     * no tool at all -- which is exactly the state it described.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void theJavadocNamesAVerbTheRealBoundaryCanPerformACraftWith() {
        Driven d = drive();
        try {
            Map<String, SyncToolSpecification> onSurface = new LinkedHashMap<>();
            for (SyncToolSpecification spec : d.surface().currentSpecs()) {
                onSurface.put(spec.tool().name(), spec);
            }

            Set<String> named = toolsNamedInJavadoc();
            List<String> canCraft = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            for (String name : named) {
                SyncToolSpecification spec = onSurface.get(name);
                if (spec == null) {
                    unknown.add(name);
                    continue;
                }
                Map<String, Object> interact = (Map<String, Object>) ((Map<String, Object>)
                        spec.tool().inputSchema().get("properties")).get("interact");
                if (interact == null) {
                    continue;
                }
                Map<String, Object> kind = (Map<String, Object>)
                        ((Map<String, Object>) interact.get("properties")).get("kind");
                List<String> values = (List<String>) kind.get("enum");
                if (values != null && values.contains("craft")) {
                    canCraft.add(name);
                }
            }

            // Non-vacuity of the comparison: a javadoc naming NOTHING satisfies "names no way to
            // craft" while leaving a prompt author with no route at all, which is the other failure.
            assertFalse("Craft's class javadoc must NAME at least one tool, or the reader is handed "
                    + "no route and the assertion below passes for the wrong reason. Named: " + named,
                    named.isEmpty());

            assertTrue("Craft's class javadoc names no verb through which a craft can actually be "
                    + "performed, so it describes a surface where crafting is impossible. Named: "
                    + named + "; of those, accepting kind='craft': " + canCraft + ". A sentence "
                    + "telling a prompt author there is no way is the defect this file exists to "
                    + "stop -- and that author then does not write the three lines that craft.",
                    !canCraft.isEmpty());

            assertTrue("Craft's class javadoc names tools that are NOT on the real model surface: "
                    + unknown + ". A javadoc citing a tool that does not exist is instance 2.13 in a "
                    + "new file: unreceipted, with the reader told a proof is there.",
                    unknown.isEmpty());
        } finally {
            d.exec().shutdown();
        }
    }

    /**
     * The second half, and the reason this file is not only about one sentence: the javadoc must name
     * the SHIPPED window implementation, asked of the type system rather than of a name list.
     *
     * <p>Dropping the {@link LiveCraftWindow} mention from {@code Craft.java} turns this red;
     * deleting {@link LiveCraftWindow} turns {@link #theRealBoundaryStillPerformsACraft} red.
     * Disjoint red sets, so neither direction is satisfied by a guard that refuses everything.
     */
    @Test
    public void theJavadocNamesTheShippedWindowImplementationWhenOneExists() {
        boolean shipped = CraftWindow.class.isAssignableFrom(LiveCraftWindow.class);
        assertTrue("this pin must have something to check: LiveCraftWindow is expected to be the "
                + "shipped CraftWindow implementation", shipped);

        String javadoc = craftClassJavadoc();
        assertTrue("a shipped CraftWindow implementation exists, so the javadoc must say so. A "
                + "sentence telling a prompt author that none exists outside the tests is the defect "
                + "this file exists to stop. Javadoc: " + javadoc,
                javadoc.contains("LiveCraftWindow"));
    }
}