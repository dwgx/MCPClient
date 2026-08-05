package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.Test;

/**
 * Teeth for the scope half of eval_java's description, which was silent about the only thing
 * about this tool a caller cannot recover from guessing wrong.
 *
 * <p>The old text was accurate and careful about the game: worker thread, marshal onto the game
 * thread via GameBridge, touching state off-thread can crash. Read plainly it draws the boundary
 * at the game -- and the boundary is not there. Measured on the live client (2026-08-05, worker
 * thread, one eval_java submission each, no kernel guard fired on any of them): ProcessBuilder
 * spawned /bin/echo (exit 0), Runtime.exec ran /usr/bin/id, Files.writeString and readString hit
 * arbitrary paths, the home directory listed 76 entries, a ServerSocket bound and a Socket
 * connected to it and carried bytes, and System.getenv returned 41 variables. Nine probes, nine
 * reached, zero denials.
 *
 * <p>{@code Ring}'s class javadoc has always said this ("rings gate <i>named tools</i>...they are
 * not a code sandbox"), and the side tables gate the tool at R-1 + SYSTEM + SE_CREATE_TOOL +
 * CAP_TOOL_CREATE -- each pinned by VALUE, verified by mutation. So the gate is not the gap. The
 * gap was that the honest sentence lived where kernel authors read it and not where the caller
 * does, and the caller is the party that has to decide whether submitting a snippet is safe.
 *
 * <p><b>What this test deliberately does NOT do:</b> it does not assert that eval_java can spawn
 * a process. Pinning the reach as expected behaviour would make this file a specification for the
 * hole, which is the shape {@code handoff-2026-08-06.md} §2(3) named -- a test that records a
 * defect instead of defending against one, and that would then have to be deleted by whoever
 * narrows the boundary later. The subject here is the description's honesty. If the owner decides
 * to make OS access a designed, separately-gated capability, this test stays true: the text would
 * change, and these assertions are about what the text must not omit while the reach is real.
 *
 * <p>Half the assertions are NEGATIVE on purpose. A description could name every API above and
 * still mislead by keeping the old game-shaped framing as its only statement of limits, so the
 * negatives fail if the scope paragraph is dropped back to a game-only warning.
 */
public class EvalJavaDescriptionNamesItsRealScopeTest {

    private static Tool tool(String name) {
        ToolRegistry reg = new ToolRegistry(new ToolContext(null, null, null, null, null));
        for (SyncToolSpecification spec : reg.all()) {
            if (spec.tool().name().equals(name)) {
                return spec.tool();
            }
        }
        throw new AssertionError("tool not found: " + name);
    }

    @Test
    public void theDescriptionSaysItIsNotASandbox() {
        String desc = tool("eval_java").description();
        assertTrue("the caller decides whether to submit code; 'not a sandbox' is the single "
                + "fact that decision turns on, and it was only in Ring's javadoc",
                desc.contains("NOT a sandbox"));
        assertTrue("and that the reach is the machine rather than the game, since the rest of "
                + "the description is entirely game-shaped and reads as the boundary",
                desc.contains("whole machine"));
    }

    @Test
    public void theDescriptionNamesTheReachThatWasMeasured() {
        String desc = tool("eval_java").description();
        assertTrue("process spawn was measured reachable", desc.contains("ProcessBuilder"));
        assertTrue("both exec paths reach, so naming one invites the other being assumed gated",
                desc.contains("Runtime.exec"));
        assertTrue("arbitrary file read AND write both reached",
                desc.contains("read and write any file"));
        assertTrue("sockets bind and connect and carry bytes", desc.contains("sockets"));
        assertTrue("the environment is readable", desc.contains("environment"));
    }

    @Test
    public void theDescriptionSeparatesBeingAllowedToCallFromWhatTheCodeMayDo() {
        String desc = tool("eval_java").description();
        assertTrue("this is the actual misreading to prevent: the R-1 gate is real and a caller "
                + "who sees it can conclude the code is contained too",
                desc.contains("do not constrain"));
        assertTrue("name the kill switch so the limit is actionable and not just a warning",
                desc.contains("SE_CREATE_TOOL"));
        assertTrue("all-or-nothing is the property that decides whether it is usable in "
                + "practice: turning it off also removes the legitimate REPL",
                desc.contains("all-or-nothing"));
    }

    @Test
    public void theDescriptionDoesNotDrawTheBoundaryAtTheGame() {
        String desc = tool("eval_java").description();
        int scope = desc.indexOf("SCOPE");
        assertTrue("the scope statement must exist to be found", scope >= 0);
        String crash = "can crash the game";
        assertTrue("the game-thread warning is still wanted and still true", desc.contains(crash));
        assertTrue("but it must not be the LAST word on limits -- a reader who stops at the "
                + "crash warning has been told the worst case is a crashed game",
                scope > desc.indexOf(crash));
    }

    @Test
    public void theKillSwitchClaimNamesEveryDoorItCloses() {
        String desc = tool("eval_java").description();
        assertTrue("send_raw_packet shares SE_CREATE_TOOL, so a caller disabling it to stop "
                + "eval_java loses packet sends too and should not learn that by surprise",
                desc.contains("send_raw_packet"));
        assertTrue("create_tool is the third door on the same privilege",
                desc.contains("create_tool"));
        assertFalse("and the switch must not be described as eval_java-specific, which is the "
                + "wrong mental model for an all-or-nothing dial",
                desc.contains("only disables eval_java"));
    }
}
