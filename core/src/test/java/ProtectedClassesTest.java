import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.TreeSet;
import net.marcloud.mcp.core.se.SeProtectedObjects;
import org.junit.Test;

/**
 * Phase 0 guard: the canonical protected-class set that redefine/retransform
 * paths consult so the privilege model can't be rewritten from inside.
 */
public class ProtectedClassesTest {

    /**
     * RENAME SAFETY NET: every FQN in the protected exact-name set must resolve to
     * a real loadable class. {@link SeProtectedObjects} pins these names as STRING
     * LITERALS; the NT-Executive package rename moves the classes, and if any pinned
     * string is not updated in lockstep the guard silently stops matching the moved
     * class — a security regression the compiler cannot catch. A stale FQN fails to
     * load here, so this test breaks loudly the moment a protected class is moved
     * without updating its pin. (Runs green today with the current names; it is the
     * tripwire for the rename.)
     */
    @Test
    public void everyProtectedNameResolvesToALoadableClass() {
        TreeSet<String> unresolved = new TreeSet<>();
        for (String fqn : SeProtectedObjects.names()) {
            try {
                Class.forName(fqn, false, getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                unresolved.add(fqn);
            }
            assertTrue("a pinned protected name must still be protected: " + fqn,
                    SeProtectedObjects.isProtected(fqn));
        }
        assertTrue("protected pins that no longer resolve (stale after a move/rename?): "
                + unresolved, unresolved.isEmpty());
    }

    @Test
    public void securityPackageIsProtectedByPrefix() {
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.se.SeClearancePolicy"));
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.se.Ring"));
        // A kernel class added later under the security package is covered
        // automatically by the prefix rule (no edit to the set required).
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.se.SeToken"));
    }

    @Test
    public void loadBearingClassesOutsideSecurityPackageAreProtected() {
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.boot.CoreAgent"));
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.boot.AgentAccess"));
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.io.IoManager"));
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.io.IoSupervisor"));
        // Regression (review finding): the L7 boundary IoProbe (deep-freeze +
        // schema validate, run by supervise() after every decision) must be
        // protected too, else redefine_class could neutralize L7. Was missing.
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.io.IoProbe"));
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.ldr.LdrRedefiner"));
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.flt.FltManager"));
    }

    @Test
    public void gameClassesAreNotProtected() {
        // The legitimate redefine_class use case must never be blocked.
        assertFalse(SeProtectedObjects.isProtected("net.minecraft.client.Minecraft"));
        assertFalse(SeProtectedObjects.isProtected("net.minecraft.network.NetworkManager"));
        assertFalse(SeProtectedObjects.isProtected("gen.SomeAiAuthoredTool"));
    }

    @Test
    public void nullAndBlankAreNotProtected() {
        assertFalse(SeProtectedObjects.isProtected(null));
        assertFalse(SeProtectedObjects.isProtected(""));
        assertFalse(SeProtectedObjects.isProtected("   "));
    }

    @Test
    public void retransformMachineryIsProtected() {
        // The dynamic-hook + seam + deep-access machinery holds Instrumentation /
        // live channels; redefining it could disable the guard from inside.
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.flt.FltDynamicManager"));
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.flt.HookTools"));
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.mm.MmAccess"));
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.flt.seam.NettyTap"));
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.flt.seam.TickInjector"));
    }

    @Test
    public void arrayAndInnerClassNamesCannotBypassTheGuard() {
        // A caller must not slip a protected class past the guard by naming its
        // array type or an inner class.
        assertTrue("array descriptor of a protected class",
                SeProtectedObjects.isProtected("[Lnet.marcloud.mcp.core.se.Ring;"));
        assertTrue("multi-dim array descriptor",
                SeProtectedObjects.isProtected("[[Lnet.marcloud.mcp.core.se.SeClearancePolicy;"));
        assertTrue("source-form array",
                SeProtectedObjects.isProtected("net.marcloud.mcp.core.se.Ring[]"));
        assertTrue("inner class of a protected class",
                SeProtectedObjects.isProtected("net.marcloud.mcp.core.io.IoManager$Inner"));
        // A game array is still not protected (legitimate use unaffected).
        assertFalse(SeProtectedObjects.isProtected("[Lnet.minecraft.network.Packet;"));
    }
}
