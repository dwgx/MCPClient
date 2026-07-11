package net.marcloud.mcp.core.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * Headless tests for the L6 object-handle layer: AccessRight bit flags + subset
 * TOCTOU check, ResourceRef scheme parsing, and ObjectManager
 * open→freeze-mask→subset-check semantics (owner scoping, per-subject cap,
 * deterministic idle reaper via an injected fake clock, frozen-target TOCTOU),
 * plus the additive no-op gate seam. Pure logic, no live game, no sleeps.
 *
 * <p>In the {@code security} package so it can reach the package-private 4-arg
 * {@link ObjectManager} ctor (fake-clock injection for a deterministic reaper).
 */
public class L6ObjectHandleTest {

    // A subject at a chosen identity (wide-open on every other dimension).
    private static SecurityContext subject(String id) {
        return new SecurityContext(id, Ring.R_MINUS_1, IntegrityLevel.SYSTEM,
                PrivilegeToken.wideOpen(), (Set<CapabilitySid>) null);
    }

    private static final int TTL_MILLIS = 1_000;
    private static final long TTL_NANOS = TTL_MILLIS * 1_000_000L;

    // ---- 1. AccessRight bits + subset (TOCTOU) ----

    @Test
    public void accessRightBitsAndSubset() {
        int rw = AccessRight.mask(AccessRight.READ, AccessRight.WRITE);
        assertEquals(AccessRight.READ.bit() | AccessRight.WRITE.bit(), rw);
        assertEquals(rw, AccessRight.parse("READ|WRITE"));
        assertEquals(rw, AccessRight.parse("read, write"));
        assertEquals(Set.of(AccessRight.READ, AccessRight.WRITE), AccessRight.decode(rw));
        assertEquals(AccessRight.decode(rw).toString(), AccessRight.render(rw));

        assertTrue("have RW grants R", AccessRight.subset(rw, AccessRight.READ.bit()));
        assertTrue("have RW grants RW", AccessRight.subset(rw, rw));
        assertFalse("have RW does NOT grant DELETE", AccessRight.subset(rw, AccessRight.DELETE.bit()));
        assertFalse("no escalation R -> RW", AccessRight.subset(AccessRight.READ.bit(), rw));
    }

    @Test
    public void accessRightParseRejectsUnknownToken() {
        try {
            AccessRight.parse("READ,BOGUS");
            fail("expected IllegalArgumentException on unknown right token");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertEquals("blank -> empty mask", 0, AccessRight.parse(""));
    }

    // ---- 2. ResourceRef parses all seven schemes ----

    @Test
    public void resourceRefParsesAllSevenSchemes() {
        assertEquals(ResourceRef.Scheme.CLASS, ResourceRef.parse("class:net.minecraft.X").scheme());
        assertEquals(ResourceRef.Scheme.FIELD, ResourceRef.parse("field:player#health").scheme());
        assertEquals(ResourceRef.Scheme.METHOD, ResourceRef.parse("method:owner#name").scheme());
        assertEquals(ResourceRef.Scheme.CHANNEL, ResourceRef.parse("channel:42").scheme());
        assertEquals(ResourceRef.Scheme.THREAD, ResourceRef.parse("thread:Server thread").scheme());
        assertEquals(ResourceRef.Scheme.FRAME, ResourceRef.parse("frame:main:3").scheme());
        assertEquals(ResourceRef.Scheme.MODULE, ResourceRef.parse("module:java.base/jdk.internal.misc").scheme());

        // target preserves everything after the first ':' (frame keeps its inner colon)
        assertEquals("main:3", ResourceRef.parse("frame:main:3").target());
        assertEquals("player#health", ResourceRef.parse("FIELD:player#health").target());
    }

    @Test
    public void resourceRefRejectsMissingPrefixAndUnknownScheme() {
        try {
            ResourceRef.parse("net.minecraft.X");
            fail("missing scheme prefix should throw");
        } catch (IllegalArgumentException expected) {
        }
        try {
            ResourceRef.parse("socket:1234");
            fail("unknown scheme should throw");
        } catch (IllegalArgumentException expected) {
        }
        try {
            ResourceRef.parse("class:");
            fail("blank target should throw");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void resourceRefAllowableRightsPerScheme() {
        assertEquals(AccessRight.mask(AccessRight.READ, AccessRight.REDEFINE),
                ResourceRef.parse("class:X").allowableRights());
        assertEquals(AccessRight.EXECUTE.bit(), ResourceRef.parse("method:X#m").allowableRights());
        assertEquals(AccessRight.REDEFINE.bit(), ResourceRef.parse("module:java.base/x").allowableRights());
        assertEquals(AccessRight.mask(AccessRight.READ, AccessRight.WRITE, AccessRight.EXECUTE),
                ResourceRef.parse("thread:t").allowableRights());
    }

    // ---- 3. open freezes the mask ----

    @Test
    public void openFreezesMask() {
        ObjectManager om = new ObjectManager(ref -> new Object(), 8, TTL_MILLIS);
        SecurityContext s = subject("alice");
        int rw = AccessRight.mask(AccessRight.READ, AccessRight.WRITE);
        ObjectHandle h1 = om.open(s, ResourceRef.parse("field:player#hp"), rw);
        assertEquals("mask frozen at open", rw, h1.mask());

        ObjectHandle h2 = om.open(s, ResourceRef.parse("field:player#hp"), rw);
        assertNotEquals("re-open yields a distinct id", h1.id(), h2.id());
    }

    // ---- 4. open rejects a mask above the scheme ceiling ----

    @Test
    public void openRejectsMaskAboveSchemeAllowable() {
        ObjectManager om = new ObjectManager(ref -> new Object(), 8, TTL_MILLIS);
        SecurityContext s = subject("alice");
        try {
            om.open(s, ResourceRef.parse("method:x#m"), AccessRight.READ.bit()); // METHOD allows only EXECUTE
            fail("READ on a METHOD ref should be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            om.open(s, ResourceRef.parse("class:x"), AccessRight.WRITE.bit()); // CLASS allows READ|REDEFINE
            fail("WRITE on a CLASS ref should be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    // ---- 5/6. subset check allows the frozen right, denies escalation (TOCTOU) ----

    @Test
    public void subsetCheckAllowsAndDeniesEscalation() {
        ObjectManager om = new ObjectManager(ref -> new Object(), 8, TTL_MILLIS);
        SecurityContext s = subject("alice");
        int rw = AccessRight.mask(AccessRight.READ, AccessRight.WRITE);
        ObjectHandle h = om.open(s, ResourceRef.parse("field:player#hp"), rw);

        assertTrue("READ within frozen RW is allowed",
                om.require(h.id(), s, AccessRight.READ.bit()).allow());

        AccessDecision denied = om.require(h.id(), s, AccessRight.DELETE.bit());
        assertFalse("DELETE escalation denied", denied.allow());
        assertEquals("L6 handle", denied.layer());
        assertTrue("reason names the frozen mask",
                denied.reason().contains(AccessRight.render(rw)));
    }

    // ---- 7. owner mismatch denied ----

    @Test
    public void ownerMismatchDenied() {
        ObjectManager om = new ObjectManager(ref -> new Object(), 8, TTL_MILLIS);
        SecurityContext a = subject("alice");
        SecurityContext b = subject("bob");
        ObjectHandle h = om.open(a, ResourceRef.parse("field:player#hp"), AccessRight.READ.bit());

        AccessDecision d = om.require(h.id(), b, AccessRight.READ.bit());
        assertFalse(d.allow());
        assertEquals("L6 handle", d.layer());
        assertTrue(d.reason().contains("alice"));
    }

    // ---- 8. closed handle denied ----

    @Test
    public void closedHandleDenied() {
        ObjectManager om = new ObjectManager(ref -> new Object(), 8, TTL_MILLIS);
        SecurityContext s = subject("alice");
        ObjectHandle h = om.open(s, ResourceRef.parse("field:player#hp"), AccessRight.READ.bit());
        om.close(h.id(), s);
        assertTrue(h.isClosed());

        AccessDecision d = om.require(h.id(), s, AccessRight.READ.bit());
        assertFalse("closed handle no longer usable", d.allow());
        assertEquals("L6 handle", d.layer());
        assertEquals("total drops after close", 0, om.total());
    }

    @Test
    public void closeIgnoresNonOwner() {
        ObjectManager om = new ObjectManager(ref -> new Object(), 8, TTL_MILLIS);
        SecurityContext a = subject("alice");
        SecurityContext b = subject("bob");
        ObjectHandle h = om.open(a, ResourceRef.parse("field:player#hp"), AccessRight.READ.bit());
        om.close(h.id(), b);                       // bob cannot close alice's handle
        assertFalse("non-owner close is a no-op", h.isClosed());
        assertTrue(om.require(h.id(), a, AccessRight.READ.bit()).allow());
    }

    // ---- 9. per-subject cap enforced ----

    @Test
    public void perSubjectCapEnforced() {
        ObjectManager om = new ObjectManager(ref -> new Object(), 2, TTL_MILLIS);
        SecurityContext s = subject("alice");
        ObjectHandle h1 = om.open(s, ResourceRef.parse("field:a#x"), AccessRight.READ.bit());
        om.open(s, ResourceRef.parse("field:b#x"), AccessRight.READ.bit());
        assertEquals(2, om.openCount("alice"));
        try {
            om.open(s, ResourceRef.parse("field:c#x"), AccessRight.READ.bit());
            fail("third open past cap=2 should throw");
        } catch (IllegalStateException expected) {
        }
        om.close(h1.id(), s);                       // free one slot
        assertEquals("count decrements on close", 1, om.openCount("alice"));
        om.open(s, ResourceRef.parse("field:c#x"), AccessRight.READ.bit()); // now succeeds
        assertEquals(2, om.openCount("alice"));
    }

    // ---- 10. idle reaper closes stale handles (deterministic fake clock) ----

    @Test
    public void idleReaperClosesStale() {
        AtomicLong now = new AtomicLong(0);
        ObjectManager om = new ObjectManager(ref -> new Object(), 8, TTL_MILLIS, now::get);
        SecurityContext s = subject("alice");
        ObjectHandle h = om.open(s, ResourceRef.parse("field:player#hp"), AccessRight.READ.bit());

        now.set(TTL_NANOS / 2);
        assertEquals("not yet idle", 0, om.reapIdle());
        assertFalse(h.isClosed());

        now.set(TTL_NANOS + 1);
        assertEquals("now past ttl", 1, om.reapIdle());
        assertTrue(h.isClosed());
        assertEquals(0, om.total());
    }

    // ---- 11. frozen target defeats TOCTOU swap ----

    @Test
    public void frozenTargetTocTou() {
        Object v1 = new Object();
        Object v2 = new Object();
        // resolver would return v2 on any call AFTER the first, but the handle froze v1.
        AtomicLong calls = new AtomicLong(0);
        ObjectManager om = new ObjectManager(
                ref -> calls.getAndIncrement() == 0 ? v1 : v2, 8, TTL_MILLIS);
        SecurityContext s = subject("alice");
        ObjectHandle h = om.open(s, ResourceRef.parse("thread:Server thread"), AccessRight.READ.bit());
        assertSame("target frozen at open", v1, h.target());
        assertNotEquals(v1, v2);
    }

    // ---- 12. gate seam is a no-op without a handle arg; enforces with one ----

    @Test
    public void gateSeamNoOpWithoutHandleArg() {
        ObjectManager om = new ObjectManager(ref -> new Object(), 8, TTL_MILLIS);
        SecurityContext s = subject("alice");

        // Non-handle tool with empty args (the isAllowed / pre-handler gate shape) -> allowed.
        assertTrue(om.checkRequest(s, new ToolRequest("scan_surroundings", Map.of(), true)).allow());

        // Bogus handle id on a handle-tool -> deny (unknown handle).
        AccessDecision unknown = om.checkRequest(s,
                new ToolRequest("dbg_read_locals", Map.of("handle", "9999"), true));
        assertFalse(unknown.allow());
        assertEquals("L6 handle", unknown.layer());

        // Malformed handle id -> deny.
        AccessDecision malformed = om.checkRequest(s,
                new ToolRequest("dbg_read_locals", Map.of("handle", "not-a-number"), true));
        assertFalse(malformed.allow());
        assertEquals("L6 handle", malformed.layer());
    }

    @Test
    public void gateSeamEnforcesPerToolRightThroughCheckRequest() {
        ObjectManager om = new ObjectManager(ref -> new Object(), 8, TTL_MILLIS);
        SecurityContext s = subject("alice");
        // Open a THREAD handle frozen READ-only.
        ObjectHandle h = om.open(s, ResourceRef.parse("thread:Server thread"), AccessRight.READ.bit());

        // dbg_read_locals needs READ -> allowed.
        assertTrue(om.checkRequest(s,
                new ToolRequest("dbg_read_locals", Map.of("handle", Long.toString(h.id())), true)).allow());

        // dbg_suspend needs EXECUTE -> denied on a READ-only handle (TOCTOU: cannot escalate).
        AccessDecision d = om.checkRequest(s,
                new ToolRequest("dbg_suspend", Map.of("handle", Long.toString(h.id())), true));
        assertFalse(d.allow());
        assertEquals("L6 handle", d.layer());
        assertTrue(d.reason().contains(AccessRight.render(AccessRight.EXECUTE.bit())));
    }
}
