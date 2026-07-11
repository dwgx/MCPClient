package net.marcloud.mcp.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.McpCore;
import org.junit.Test;

/**
 * Regression for the non-loopback bind guard (repo-gap-survey finding #1). The
 * SECURITY.md invariant is that the REST facade must not be exposed off-host
 * without auth; McpCore uses {@link McpCore#isNonLoopback} to decide when a token
 * is mandatory. This pins the classification the guard depends on.
 *
 * <p>The pre-fix code had no such guard at all — 0.0.0.0 would start the facade
 * with zero auth.
 */
public class McpCoreBindGuardTest {

    @Test
    public void loopbackFormsAreNotFlagged() {
        assertFalse("127.0.0.1 is loopback", McpCore.isNonLoopback("127.0.0.1"));
        assertFalse("localhost is loopback", McpCore.isNonLoopback("localhost"));
        assertFalse("IPv6 loopback", McpCore.isNonLoopback("::1"));
        assertFalse("blank/unset defaults to loopback", McpCore.isNonLoopback(""));
        assertFalse("null defaults to loopback", McpCore.isNonLoopback(null));
    }

    @Test
    public void wildcardAndExternalFormsAreFlagged() {
        assertTrue("0.0.0.0 wildcard is exposed", McpCore.isNonLoopback("0.0.0.0"));
        assertTrue(":: wildcard is exposed", McpCore.isNonLoopback("::"));
        assertTrue("a non-loopback literal is exposed", McpCore.isNonLoopback("10.0.0.5"));
        // Fail-safe: an unresolvable host is treated as exposed rather than assumed safe.
        assertTrue("unresolvable host fails safe to exposed",
                McpCore.isNonLoopback("no-such-host.invalid"));
    }
}
