package net.marcloud.mcp.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

/**
 * SMOKE: production {@link McpCore#start()} really does come up headless, and its startup
 * self-check really runs inside it.
 *
 * <p>This is the evidence behind the design decision. The registrations were factored into
 * {@link McpCore#registerBuiltins} so a unit test can drive them — but the more important
 * question was whether {@code start()} itself is drivable at all, since the assignment turns
 * on the answer. It is: no game, no premain agent, no {@code -javaagent}. The socket/hook
 * branches are non-fatal by design, and the assertion here is that a clean gate table
 * produces NO security complaint from the check {@code start()} runs.
 *
 * <p>The socket bind (127.0.0.1:25599) is real, so {@code stop()} runs in a finally.
 */
public final class McpCoreHeadlessStartTest {

    @Test
    public void startComesUpHeadlessAndTheGateCheckStaysSilent() {
        // Capture stderr: the self-check writes there, and this test's whole point is that a
        // COMPLETE gate table makes it say nothing.
        PrintStream realErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        String previousHttp = System.getProperty("mcp.core.http");
        System.setProperty("mcp.core.http", "false");
        McpCore core = new McpCore();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            core.start();
        } finally {
            System.setErr(realErr);
            if (previousHttp == null) {
                System.clearProperty("mcp.core.http");
            } else {
                System.setProperty("mcp.core.http", previousHttp);
            }
        }
        try {
            String err = captured.toString(StandardCharsets.UTF_8);
            assertTrue("start() must actually have wired the stack; expected the policy banner "
                    + "in stderr but got: " + err,
                    err.contains("[MCP Core] initial clearance"));
            assertFalse("the gate self-check reported a gap on a COMPLETE gate table — it would "
                            + "cry wolf on every healthy boot and operators would learn to "
                            + "ignore it. stderr was: " + err,
                    err.contains("gate coverage is INCOMPLETE"));
        } finally {
            core.stop();
        }
    }
}