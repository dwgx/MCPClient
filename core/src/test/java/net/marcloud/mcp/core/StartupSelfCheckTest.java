package net.marcloud.mcp.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Set;
import java.util.TreeSet;

import net.marcloud.mcp.core.io.Capability;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.io.transport.ToolRegistry;
import net.marcloud.mcp.core.se.Ring;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import org.junit.Test;

/**
 * The startup self-check ({@link McpCore#reportGateGaps}) must actually FIRE.
 *
 * <p>Its whole value is that a builtin wired into production without a gate row is reported
 * loudly, naming the tool. A check that has never gone red is not a check, so this drives the
 * real production path with a deliberately under-gated tool bolted on and requires the
 * diagnostic to name it — then requires the shipped configuration to stay silent.
 *
 * <p>Non-vacuous in both directions: the same registry is audited once dirty (must report,
 * and the message must contain the tool's name) and once with the tool removed (must not
 * report), so a "report everything" or "report nothing" implementation both fail here.
 */
public final class StartupSelfCheckTest {

    /** Under-gated on purpose: no Ring row, no L3 write, no L4 privilege. */
    private static SyncToolSpecification ungatedStub(String name) {
        Tool t = Tool.builder().name(name).description("deliberately ungated " + name)
                .inputSchema(java.util.Map.of("type", "object", "properties", java.util.Map.of()))
                .build();
        return new SyncToolSpecification(t, (ex, req) ->
                CallToolResult.builder().addTextContent("ran").isError(false).build());
    }

    /** Production's registry, via the same single registration method start() calls. */
    private static IoManager productionRegistry(IoSupervisor exec) {
        IoManager reg = new net.marcloud.mcp.core.io.IoManager(exec,
                new net.marcloud.mcp.core.se.SeLocalMonitor(
                        new net.marcloud.mcp.core.se.SeClearancePolicy(Ring.R_MINUS_1, "tok"),
                        net.marcloud.mcp.core.se.SeToken.wideOpen()));
        new McpCore().registerBuiltins(reg, reg.engine(), null,
                new net.marcloud.mcp.core.io.transport.ToolContext(null, null, null, null, null),
                new net.marcloud.mcp.core.ldr.LdrEngine(StartupSelfCheckTest.class.getClassLoader()),
                new net.marcloud.mcp.core.flt.FltManager(new net.marcloud.mcp.core.ke.event.EventBus()),
                new net.marcloud.mcp.core.flt.FltDynamicManager(null,
                        new net.marcloud.mcp.core.ke.event.EventBus()),
                new net.marcloud.mcp.core.flt.seam.SeamController(
                        new net.marcloud.mcp.core.ke.event.EventBus(), new GameAccess()),
                net.marcloud.mcp.core.drivers.act.ActRuntime.INSTANCE);
        return reg;
    }

    /**
     * The shipped surface must pass silently — the self-check must not cry wolf on a build
     * whose gate tables are complete, or operators will learn to ignore it.
     */
    @Test
    public void aCompleteGateTablePassesTheStartupCheckSilently() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        try {
            McpCore.reportGateGaps(productionRegistry(exec));
        } finally {
            exec.shutdown();
        }
    }

    /**
     * An ungated tool bolted onto the PRODUCTION registry must make the check report it by
     * name, in warn mode and — under {@code -Dmcp.core.gateAudit=fail} — refuse to serve.
     */
    @Test
    public void anUngatedProductionToolIsReportedByName() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        try {
            IoManager reg = productionRegistry(exec);
            Set<String> before = new TreeSet<>(reg.names());
            reg.register("smoke_ungated_tool", ungatedStub("smoke_ungated_tool"), null,
                    "deliberately ungated", true, Ring.forBuiltin("smoke_ungated_tool", Ring.R3));
            assertTrue("the tool must actually be registered or this proves nothing",
                    new TreeSet<>(reg.names()).contains("smoke_ungated_tool"));

            // Control: removing it returns the registry to a passing state, so a positive
            // below cannot be an artefact of a registry that always fails.
            IoSupervisor clean = new IoSupervisor(4, 2000L);
            try {
                McpCore.reportGateGaps(productionRegistry(clean));
            } finally {
                clean.shutdown();
            }

            // 1) warn mode: must not throw, and the audit must name the tool.
            net.marcloud.mcp.core.se.BuiltinGateAudit.Report report =
                    net.marcloud.mcp.core.se.BuiltinGateAudit.audit(reg);
            assertFalse("an ungated production tool must be reported", report.clean());
            assertTrue("the report must NAME the offending tool, not just say something is "
                            + "wrong: " + report.message(),
                    report.message().contains("smoke_ungated_tool"));
            McpCore.reportGateGaps(reg);   // warn-only: must not throw

            // 2) fail mode: a hardened deployment must refuse to serve rather than warn.
            System.setProperty("mcp.core.gateAudit", "fail");
            try {
                McpCore.reportGateGaps(reg);
                fail("-Dmcp.core.gateAudit=fail must abort startup when a tool is ungated");
            } catch (IllegalStateException expected) {
                assertTrue("the refusal must name the tool: " + expected.getMessage(),
                        expected.getMessage().contains("smoke_ungated_tool"));
            } finally {
                System.clearProperty("mcp.core.gateAudit");
            }

            Set<String> after = new TreeSet<>(reg.names());
            after.remove("smoke_ungated_tool");
            assertEquals("bolting on the probe must not have disturbed the rest of the surface",
                    before, after);
        } finally {
            exec.shutdown();
        }
    }

    /** The ring table is the single source both sides read; a builtin must resolve through it. */
    @Test
    public void productionRegistersThroughTheRingTable() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        try {
            for (Capability c : productionRegistry(exec).capabilities()) {
                assertTrue(c.name() + " is registered with ring " + c.ring().tag()
                                + " but declares no Ring row, so its gate falls back to R3 "
                                + "while every surface reports " + c.ring().tag(),
                        Ring.forBuiltin(c.name(), null) != null);
            }
        } finally {
            exec.shutdown();
        }
    }
}