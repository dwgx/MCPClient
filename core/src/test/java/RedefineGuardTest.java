import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import net.marcloud.mcp.core.hotload.Redefiner;
import net.marcloud.mcp.core.security.PermissionPolicy;
import org.junit.Test;

/**
 * Phase 0 enforcement: the redefine path refuses protected Core classes, and the
 * Instrumentation handle is no longer a public global. These close the two known
 * self-modification holes (redefine-the-guard, and the ungated
 * {@code CoreAgent.instrumentation()}).
 */
public class RedefineGuardTest {

    @Test
    public void redefineRefusesProtectedClass() {
        Redefiner r = new Redefiner();
        // The protected-class guard runs BEFORE any Instrumentation check, so this
        // throws even with no agent loaded (headless-provable). PermissionPolicy
        // is a protected security-package class.
        try {
            r.redefine(PermissionPolicy.class, new byte[]{1, 2, 3});
            fail("expected redefine of a protected class to be refused");
        } catch (IllegalStateException expected) {
            assertTrue("message names the protection",
                    expected.getMessage().toLowerCase().contains("protected"));
        } catch (Exception e) {
            fail("expected IllegalStateException, got " + e);
        }
    }

    @Test
    public void instrumentationAccessorIsNotPublic() throws Exception {
        // Hole (ii): CoreAgent.instrumentation() used to be public static — any
        // in-process code could grab full Instrumentation. It is now package-
        // private, reachable only through the agent package's AgentAccess seam.
        Class<?> coreAgent = Class.forName("net.marcloud.mcp.core.agent.CoreAgent");
        Method m = coreAgent.getDeclaredMethod("instrumentation");
        assertFalse("CoreAgent.instrumentation() must not be public",
                Modifier.isPublic(m.getModifiers()));
    }

    @Test
    public void protectedClassGuardIsIndependentOfAgentPresence() {
        // In the test JVM no -javaagent is loaded, so isAvailable() is false; the
        // guard must still reject a protected target rather than fall through to
        // an "agent missing" path.
        Redefiner r = new Redefiner();
        assertFalse("no agent in test JVM", r.isAvailable());
        try {
            r.redefine(net.marcloud.mcp.core.security.Ring.class, new byte[]{0});
            fail("expected refusal");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("protected"));
        } catch (Exception e) {
            fail("expected IllegalStateException, got " + e);
        }
    }
}
