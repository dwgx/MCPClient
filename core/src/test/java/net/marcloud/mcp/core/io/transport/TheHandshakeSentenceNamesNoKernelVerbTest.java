package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import net.marcloud.mcp.core.McpCore;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.flt.FltDynamicManager;
import net.marcloud.mcp.core.flt.FltManager;
import net.marcloud.mcp.core.flt.seam.SeamController;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ldr.LdrEngine;
import net.marcloud.mcp.core.ob.ObManager;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeToken;

import org.junit.Test;

/**
 * The handshake sentence is the only prose this server sends a model, and it is the one place a
 * weak model is told what its capabilities are. It used to name {@code create_tool} — the verb
 * that MANUFACTURES the kernel surface — in the same breath as {@code list_capabilities}, and
 * that verb had just been ruled kernel-layered. The server was therefore instructing a model to
 * reach for the most dangerous verb in the system, immediately after hiding it.
 *
 * <p><b>Why this test reads the layer table and not a list of forbidden words.</b> The obvious
 * shape — {@code assertFalse(sentence.contains("create_tool"))} — is the stale-gate-row shape: the
 * list in the test would be a second copy of {@link ToolRegistry}'s table, and the next kernel
 * verb to be declared would sail past it. Instead this extracts every tool-shaped name out of
 * {@link SocketTransportServer#INSTRUCTIONS} and asks {@link ToolRegistry#layerOf} about each
 * one, so the ruling is enforced against the single authority and a sentence naming
 * {@code eval_java}, {@code debug_manage} or any future kernel row goes red with no edit here.
 *
 * <p><b>Why "tool-shaped" rather than every word.</b> The sentence is prose, and most of its words
 * are not tool names. The scan matches the snake_case spelling every tool in this surface uses
 * (the same shape {@code DescriptionsNameToolsThatExistTest} uses for descriptions), so the test
 * asks about names and not about English.
 *
 * <p><b>Non-vacuity, in both directions.</b> The sentence must actually NAME a tool — an empty or
 * orientation-free sentence satisfies "names no kernel verb" while leaving the model with no way
 * to find out what it can do, which is the other half of the ruling. And the names it does carry
 * must be reachable through the driven model surface, so "names a model-facing verb" cannot be
 * satisfied by naming one that is declared GAME but not registered.
 */
public final class TheHandshakeSentenceNamesNoKernelVerbTest {

    /** snake_case -- how every tool name in this surface is spelled. */
    private static final Pattern TOOL_LIKE =
            Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+");

    /** Every tool-shaped name the handshake sentence spells, in order, deduplicated. */
    private static Set<String> namesInTheSentence() {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TOOL_LIKE.matcher(SocketTransportServer.INSTRUCTIONS);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private record Driven(McpCore core, IoManager audited, IoManager surface, IoSupervisor exec) {
    }

    /** Drive production's own {@code registerBuiltins} — the single registration site. */
    private static Driven drive() {
        SeLocalMonitor engine = new SeLocalMonitor(
                new SeClearancePolicy(Ring.R_MINUS_1, "tok"), SeToken.wideOpen());
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        IoManager audited = new IoManager(exec, engine);
        EventBus bus = new EventBus();
        McpCore core = new McpCore();
        core.registerBuiltins(audited, engine, null,
                new ToolContext(null, null, null, null, null),
                new LdrEngine(TheHandshakeSentenceNamesNoKernelVerbTest.class.getClassLoader()),
                new FltManager(bus), new FltDynamicManager(null, bus),
                new SeamController(bus, core.gameAccess()),
                ActRuntime.INSTANCE);
        return new Driven(core, audited, core.modelSurface(), exec);
    }

    /**
     * Every tool name the handshake sentence spells must be MODEL-FACING, asked of the layer
     * table itself rather than of a list copied into this file.
     */
    @Test
    public void theSentenceNamesNoKernelLayeredVerb() {
        Set<String> names = namesInTheSentence();
        Set<String> kernel = new TreeSet<>();
        for (String name : names) {
            if (ToolRegistry.layerOf(name) == ToolRegistry.Layer.KERNEL) {
                kernel.add(name);
            }
        }
        assertTrue("the handshake sentence must not name a kernel-layered verb — the model cannot "
                        + "call one, and being told to is the defect this test exists to stop. "
                        + "Kernel-layered names in the sentence: " + kernel
                        + ". Sentence: " + SocketTransportServer.INSTRUCTIONS,
                kernel.isEmpty());
    }

    /**
     * The surviving half is a live capability, not a promise: the sentence must still hand a
     * just-connected model a way to discover what it can do.
     *
     * <p>Non-vacuous in both directions. An empty scan fails here, so "names no kernel verb"
     * cannot be satisfied by deleting the sentence; and each named verb must actually be on the
     * driven model surface, so a sentence naming a declared-but-unregistered GAME row also fails.
     */
    @Test
    public void theSentenceStillNamesAReachableModelFacingVerb() {
        Set<String> names = namesInTheSentence();
        assertFalse("the handshake sentence must NAME at least one tool: a model that has just "
                        + "connected is handed no inventory, so with no verb to call it has no "
                        + "way to find out what it can do. Sentence: "
                        + SocketTransportServer.INSTRUCTIONS,
                names.isEmpty());

        Driven d = drive();
        try {
            Set<String> onSurface = new TreeSet<>(d.surface().names());
            // Non-vacuity of the comparison below: if the two registries held the same set, then
            // "names a reachable model-facing verb" would be satisfied by any name at all and the
            // layer check in the sibling test would be the only thing standing.
            assertTrue("the audited registry must be strictly larger than the model surface, or "
                            + "these assertions cannot fail: "
                            + d.audited().names().size() + " vs " + onSurface.size(),
                    d.audited().names().size() > onSurface.size());

            for (String name : names) {
                assertEquals("the sentence names '" + name + "' and ToolRegistry says "
                                + ToolRegistry.layerOf(name) + ", so the model must be able to "
                                + "call it. Sentence: " + SocketTransportServer.INSTRUCTIONS,
                        ToolRegistry.Layer.GAME, ToolRegistry.layerOf(name));
                assertTrue("the sentence names '" + name + "' but it is not on the model surface, "
                                + "so the sentence would be telling the model to call something "
                                + "it cannot. Surface: " + onSurface,
                        onSurface.contains(name));
                CallToolResult reachable = d.surface().invoke(name, java.util.Map.of());
                assertTrue("the sentence names '" + name + "', so invoking it through the model "
                                + "surface must not return null — null is silence, which a model "
                                + "reads as 'there is no such tool'",
                        reachable != null);
            }
        } finally {
            d.exec().shutdown();
        }
    }
}