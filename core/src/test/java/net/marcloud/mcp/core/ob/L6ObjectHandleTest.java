package net.marcloud.mcp.core.ob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import net.marcloud.mcp.core.se.CapabilitySid;
import net.marcloud.mcp.core.se.IntegrityLevel;
import net.marcloud.mcp.core.se.PrivilegeToken;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeAccessCheck;
import net.marcloud.mcp.core.io.IoRequestPacket;
import net.marcloud.mcp.core.se.SeToken;
import org.junit.Test;

/**
 * Headless tests for the L6 object-handle layer: ObAccessMask bit flags + subset
 * TOCTOU check, ObRef scheme parsing, and ObManager
 * open→freeze-mask→subset-check semantics (owner scoping, per-subject cap,
 * deterministic idle reaper via an injected fake clock, frozen-target TOCTOU),
 * plus the additive no-op gate seam. Pure logic, no live game, no sleeps.
 *
 * <p>In the {@code security} package so it can reach the package-private 4-arg
 * {@link ObManager} ctor (fake-clock injection for a deterministic reaper).
 */
public class L6ObjectHandleTest {

    // A subject at a chosen identity (wide-open on every other dimension).
    private static SeToken subject(String id) {
        return new SeToken(id, Ring.R_MINUS_1, IntegrityLevel.SYSTEM,
                PrivilegeToken.wideOpen(), (Set<CapabilitySid>) null);
    }

    private static final int TTL_MILLIS = 1_000;
    private static final long TTL_NANOS = TTL_MILLIS * 1_000_000L;

    // ---- 1. ObAccessMask bits + subset (TOCTOU) ----

    @Test
    public void accessRightBitsAndSubset() {
        int rw = ObAccessMask.mask(ObAccessMask.READ, ObAccessMask.WRITE);
        assertEquals(ObAccessMask.READ.bit() | ObAccessMask.WRITE.bit(), rw);
        assertEquals(rw, ObAccessMask.parse("READ|WRITE"));
        assertEquals(rw, ObAccessMask.parse("read, write"));
        assertEquals(Set.of(ObAccessMask.READ, ObAccessMask.WRITE), ObAccessMask.decode(rw));
        assertEquals(ObAccessMask.decode(rw).toString(), ObAccessMask.render(rw));

        assertTrue("have RW grants R", ObAccessMask.subset(rw, ObAccessMask.READ.bit()));
        assertTrue("have RW grants RW", ObAccessMask.subset(rw, rw));
        assertFalse("have RW does NOT grant DELETE", ObAccessMask.subset(rw, ObAccessMask.DELETE.bit()));
        assertFalse("no escalation R -> RW", ObAccessMask.subset(ObAccessMask.READ.bit(), rw));
    }

    @Test
    public void accessRightParseRejectsUnknownToken() {
        try {
            ObAccessMask.parse("READ,BOGUS");
            fail("expected IllegalArgumentException on unknown right token");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertEquals("blank -> empty mask", 0, ObAccessMask.parse(""));
    }

    // ---- 2. ObRef parses all seven schemes ----

    @Test
    public void resourceRefParsesAllSevenSchemes() {
        assertEquals(ObRef.Scheme.CLASS, ObRef.parse("class:net.minecraft.X").scheme());
        assertEquals(ObRef.Scheme.FIELD, ObRef.parse("field:player#health").scheme());
        assertEquals(ObRef.Scheme.METHOD, ObRef.parse("method:owner#name").scheme());
        assertEquals(ObRef.Scheme.CHANNEL, ObRef.parse("channel:42").scheme());
        assertEquals(ObRef.Scheme.THREAD, ObRef.parse("thread:Server thread").scheme());
        assertEquals(ObRef.Scheme.FRAME, ObRef.parse("frame:main:3").scheme());
        assertEquals(ObRef.Scheme.MODULE, ObRef.parse("module:java.base/jdk.internal.misc").scheme());

        // target preserves everything after the first ':' (frame keeps its inner colon)
        assertEquals("main:3", ObRef.parse("frame:main:3").target());
        assertEquals("player#health", ObRef.parse("FIELD:player#health").target());
    }

    @Test
    public void resourceRefRejectsMissingPrefixAndUnknownScheme() {
        try {
            ObRef.parse("net.minecraft.X");
            fail("missing scheme prefix should throw");
        } catch (IllegalArgumentException expected) {
        }
        try {
            ObRef.parse("socket:1234");
            fail("unknown scheme should throw");
        } catch (IllegalArgumentException expected) {
        }
        try {
            ObRef.parse("class:");
            fail("blank target should throw");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void resourceRefAllowableRightsPerScheme() {
        assertEquals(ObAccessMask.mask(ObAccessMask.READ, ObAccessMask.REDEFINE),
                ObRef.parse("class:X").allowableRights());
        assertEquals(ObAccessMask.EXECUTE.bit(), ObRef.parse("method:X#m").allowableRights());
        assertEquals(ObAccessMask.REDEFINE.bit(), ObRef.parse("module:java.base/x").allowableRights());
        assertEquals(ObAccessMask.mask(ObAccessMask.READ, ObAccessMask.WRITE, ObAccessMask.EXECUTE),
                ObRef.parse("thread:t").allowableRights());
    }

    // ---- 3. open freezes the mask ----

    @Test
    public void openFreezesMask() {
        ObManager om = new ObManager(ref -> new Object(), 8, TTL_MILLIS);
        SeToken s = subject("alice");
        int rw = ObAccessMask.mask(ObAccessMask.READ, ObAccessMask.WRITE);
        ObHandle h1 = om.open(s, ObRef.parse("field:player#hp"), rw);
        assertEquals("mask frozen at open", rw, h1.mask());

        ObHandle h2 = om.open(s, ObRef.parse("field:player#hp"), rw);
        assertNotEquals("re-open yields a distinct id", h1.id(), h2.id());
    }

    // ---- 4. open rejects a mask above the scheme ceiling ----

    @Test
    public void openRejectsMaskAboveSchemeAllowable() {
        ObManager om = new ObManager(ref -> new Object(), 8, TTL_MILLIS);
        SeToken s = subject("alice");
        try {
            om.open(s, ObRef.parse("method:x#m"), ObAccessMask.READ.bit()); // METHOD allows only EXECUTE
            fail("READ on a METHOD ref should be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            om.open(s, ObRef.parse("class:x"), ObAccessMask.WRITE.bit()); // CLASS allows READ|REDEFINE
            fail("WRITE on a CLASS ref should be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    // ---- 5/6. subset check allows the frozen right, denies escalation (TOCTOU) ----

    @Test
    public void subsetCheckAllowsAndDeniesEscalation() {
        ObManager om = new ObManager(ref -> new Object(), 8, TTL_MILLIS);
        SeToken s = subject("alice");
        int rw = ObAccessMask.mask(ObAccessMask.READ, ObAccessMask.WRITE);
        ObHandle h = om.open(s, ObRef.parse("field:player#hp"), rw);

        assertTrue("READ within frozen RW is allowed",
                om.require(h.id(), s, ObAccessMask.READ.bit()).allow());

        SeAccessCheck denied = om.require(h.id(), s, ObAccessMask.DELETE.bit());
        assertFalse("DELETE escalation denied", denied.allow());
        assertEquals("L6 handle", denied.layer());
        assertTrue("reason names the frozen mask",
                denied.reason().contains(ObAccessMask.render(rw)));
    }

    // ---- 7. owner mismatch denied ----

    @Test
    public void ownerMismatchDenied() {
        ObManager om = new ObManager(ref -> new Object(), 8, TTL_MILLIS);
        SeToken a = subject("alice");
        SeToken b = subject("bob");
        ObHandle h = om.open(a, ObRef.parse("field:player#hp"), ObAccessMask.READ.bit());

        SeAccessCheck d = om.require(h.id(), b, ObAccessMask.READ.bit());
        assertFalse(d.allow());
        assertEquals("L6 handle", d.layer());
        assertTrue(d.reason().contains("alice"));
    }

    // ---- 8. closed handle denied ----

    @Test
    public void closedHandleDenied() {
        ObManager om = new ObManager(ref -> new Object(), 8, TTL_MILLIS);
        SeToken s = subject("alice");
        ObHandle h = om.open(s, ObRef.parse("field:player#hp"), ObAccessMask.READ.bit());
        om.close(h.id(), s);
        assertTrue(h.isClosed());

        SeAccessCheck d = om.require(h.id(), s, ObAccessMask.READ.bit());
        assertFalse("closed handle no longer usable", d.allow());
        assertEquals("L6 handle", d.layer());
        assertEquals("total drops after close", 0, om.total());
    }

    @Test
    public void closeIgnoresNonOwner() {
        ObManager om = new ObManager(ref -> new Object(), 8, TTL_MILLIS);
        SeToken a = subject("alice");
        SeToken b = subject("bob");
        ObHandle h = om.open(a, ObRef.parse("field:player#hp"), ObAccessMask.READ.bit());
        om.close(h.id(), b);                       // bob cannot close alice's handle
        assertFalse("non-owner close is a no-op", h.isClosed());
        assertTrue(om.require(h.id(), a, ObAccessMask.READ.bit()).allow());
    }

    // ---- 9. per-subject cap enforced ----

    @Test
    public void perSubjectCapEnforced() {
        ObManager om = new ObManager(ref -> new Object(), 2, TTL_MILLIS);
        SeToken s = subject("alice");
        ObHandle h1 = om.open(s, ObRef.parse("field:a#x"), ObAccessMask.READ.bit());
        om.open(s, ObRef.parse("field:b#x"), ObAccessMask.READ.bit());
        assertEquals(2, om.openCount("alice"));
        try {
            om.open(s, ObRef.parse("field:c#x"), ObAccessMask.READ.bit());
            fail("third open past cap=2 should throw");
        } catch (IllegalStateException expected) {
        }
        om.close(h1.id(), s);                       // free one slot
        assertEquals("count decrements on close", 1, om.openCount("alice"));
        om.open(s, ObRef.parse("field:c#x"), ObAccessMask.READ.bit()); // now succeeds
        assertEquals(2, om.openCount("alice"));
    }

    // ---- 10. idle reaper closes stale handles (deterministic fake clock) ----

    @Test
    public void idleReaperClosesStale() {
        AtomicLong now = new AtomicLong(0);
        ObManager om = new ObManager(ref -> new Object(), 8, TTL_MILLIS, now::get);
        SeToken s = subject("alice");
        ObHandle h = om.open(s, ObRef.parse("field:player#hp"), ObAccessMask.READ.bit());

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
        ObManager om = new ObManager(
                ref -> calls.getAndIncrement() == 0 ? v1 : v2, 8, TTL_MILLIS);
        SeToken s = subject("alice");
        ObHandle h = om.open(s, ObRef.parse("thread:Server thread"), ObAccessMask.READ.bit());
        assertSame("target frozen at open", v1, h.target());
        assertNotEquals(v1, v2);
    }

    // ---- 12. gate seam is a no-op without a handle arg; enforces with one ----

    @Test
    public void gateSeamNoOpWithoutHandleArg() {
        ObManager om = new ObManager(ref -> new Object(), 8, TTL_MILLIS);
        SeToken s = subject("alice");

        // Non-handle tool with empty args (the isAllowed / pre-handler gate shape) -> allowed.
        assertTrue(om.checkRequest(s, new IoRequestPacket("scan_surroundings", Map.of(), true)).allow());

        // Bogus handle id on a handle-tool -> deny (unknown handle).
        SeAccessCheck unknown = om.checkRequest(s,
                new IoRequestPacket("debug_read_local", Map.of("handle", "9999"), true));
        assertFalse(unknown.allow());
        assertEquals("L6 handle", unknown.layer());

        // Malformed handle id -> deny.
        SeAccessCheck malformed = om.checkRequest(s,
                new IoRequestPacket("debug_read_local", Map.of("handle", "not-a-number"), true));
        assertFalse(malformed.allow());
        assertEquals("L6 handle", malformed.layer());
    }

    @Test
    public void gateSeamEnforcesPerToolRightThroughCheckRequest() {
        ObManager om = new ObManager(ref -> new Object(), 8, TTL_MILLIS);
        SeToken s = subject("alice");
        // Open a THREAD handle frozen READ-only.
        ObHandle h = om.open(s, ObRef.parse("thread:Server thread"), ObAccessMask.READ.bit());

        // debug_read_local needs READ -> allowed.
        assertTrue(om.checkRequest(s,
                new IoRequestPacket("debug_read_local", Map.of("handle", Long.toString(h.id())), true)).allow());

        // debug_suspend_thread needs EXECUTE -> denied on a READ-only handle (TOCTOU: cannot escalate).
        SeAccessCheck d = om.checkRequest(s,
                new IoRequestPacket("debug_suspend_thread", Map.of("handle", Long.toString(h.id())), true));
        assertFalse(d.allow());
        assertEquals("L6 handle", d.layer());
        assertTrue(d.reason().contains(ObAccessMask.render(ObAccessMask.EXECUTE.bit())));
    }
}
