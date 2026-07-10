import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.security.ProtectedClasses;
import org.junit.Test;

/**
 * Phase 0 guard: the canonical protected-class set that redefine/retransform
 * paths consult so the privilege model can't be rewritten from inside.
 */
public class ProtectedClassesTest {

    @Test
    public void securityPackageIsProtectedByPrefix() {
        assertTrue(ProtectedClasses.isProtected("net.marcloud.mcp.core.security.PermissionPolicy"));
        assertTrue(ProtectedClasses.isProtected("net.marcloud.mcp.core.security.Ring"));
        // A kernel class added later under the security package is covered
        // automatically by the prefix rule (no edit to the set required).
        assertTrue(ProtectedClasses.isProtected("net.marcloud.mcp.core.security.SecurityContext"));
    }

    @Test
    public void loadBearingClassesOutsideSecurityPackageAreProtected() {
        assertTrue(ProtectedClasses.isProtected("net.marcloud.mcp.core.agent.CoreAgent"));
        assertTrue(ProtectedClasses.isProtected("net.marcloud.mcp.core.agent.AgentAccess"));
        assertTrue(ProtectedClasses.isProtected("net.marcloud.mcp.core.registry.CapabilityRegistry"));
        assertTrue(ProtectedClasses.isProtected("net.marcloud.mcp.core.registry.SafeToolExecutor"));
        assertTrue(ProtectedClasses.isProtected("net.marcloud.mcp.core.hotload.Redefiner"));
        assertTrue(ProtectedClasses.isProtected("net.marcloud.mcp.core.hook.HookManager"));
    }

    @Test
    public void gameClassesAreNotProtected() {
        // The legitimate redefine_class use case must never be blocked.
        assertFalse(ProtectedClasses.isProtected("net.minecraft.client.Minecraft"));
        assertFalse(ProtectedClasses.isProtected("net.minecraft.network.NetworkManager"));
        assertFalse(ProtectedClasses.isProtected("gen.SomeAiAuthoredTool"));
    }

    @Test
    public void nullAndBlankAreNotProtected() {
        assertFalse(ProtectedClasses.isProtected(null));
        assertFalse(ProtectedClasses.isProtected(""));
        assertFalse(ProtectedClasses.isProtected("   "));
    }

    @Test
    public void retransformMachineryIsProtected() {
        // The dynamic-hook + seam + deep-access machinery holds Instrumentation /
        // live channels; redefining it could disable the guard from inside.
        assertTrue(ProtectedClasses.isProtected("net.marcloud.mcp.core.hook.DynamicHookManager"));
        assertTrue(ProtectedClasses.isProtected("net.marcloud.mcp.core.hook.HookTools"));
        assertTrue(ProtectedClasses.isProtected("net.marcloud.mcp.core.deepaccess.DeepAccess"));
        assertTrue(ProtectedClasses.isProtected("net.marcloud.mcp.core.seam.NettyTap"));
        assertTrue(ProtectedClasses.isProtected("net.marcloud.mcp.core.seam.TickInjector"));
    }

    @Test
    public void arrayAndInnerClassNamesCannotBypassTheGuard() {
        // A caller must not slip a protected class past the guard by naming its
        // array type or an inner class.
        assertTrue("array descriptor of a protected class",
                ProtectedClasses.isProtected("[Lnet.marcloud.mcp.core.security.Ring;"));
        assertTrue("multi-dim array descriptor",
                ProtectedClasses.isProtected("[[Lnet.marcloud.mcp.core.security.PermissionPolicy;"));
        assertTrue("source-form array",
                ProtectedClasses.isProtected("net.marcloud.mcp.core.security.Ring[]"));
        assertTrue("inner class of a protected class",
                ProtectedClasses.isProtected("net.marcloud.mcp.core.registry.CapabilityRegistry$Inner"));
        // A game array is still not protected (legitimate use unaffected).
        assertFalse(ProtectedClasses.isProtected("[Lnet.minecraft.network.Packet;"));
    }
}
