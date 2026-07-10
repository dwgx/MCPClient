import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.deepaccess.DeepAccess;
import net.marcloud.mcp.core.deepaccess.DeepAccessException;
import net.marcloud.mcp.core.security.AllowAllGate;
import net.marcloud.mcp.core.security.DeepAccessProtectedBase;
import net.marcloud.mcp.core.security.ProtectedClasses;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression test for LOW#17: DeepAccess must guard the resolved DECLARING
 * (super)class of a field/method, not only the runtime/start class.
 *
 * <p>{@link UnprotectedSub} is a runtime class whose own FQN is NOT protected, so
 * it sails past the initial {@code guardProtected(t.getClass())} check. But its
 * inherited members are declared on {@link DeepAccessProtectedBase}, which lives
 * under {@code net.marcloud.mcp.core.security.*} and IS protected. Before the fix
 * the hierarchy walk resolved those members and proceeded to mutate/invoke them;
 * these tests assert every mutating path now refuses on the declaring class.
 *
 * <p>Non-vacuous: against the old behavior each call either succeeded (no throw →
 * {@code fail}) or threw a non-"protected" error (assertion fails). The gate is
 * {@link AllowAllGate} so the only thing under test is the ProtectedClasses guard.
 */
public class DeepAccessDeclaringGuardTest {

    private DeepAccess deepAccess;

    /** Runtime class is NOT protected; its super IS. */
    public static class UnprotectedSub extends DeepAccessProtectedBase {
    }

    /**
     * The exact refusal the declaring-class guard emits — includes the PROTECTED
     * base's FQN. Only {@code guardProtected(DeepAccessProtectedBase.class)} can
     * produce this, so matching it (not the loose substring "protected", which the
     * runtime class name {@code UnprotectedSub} also contains) proves the guard on
     * the declaring class fired rather than some unrelated reflection error.
     */
    private static final String EXPECTED_REFUSAL =
            "refusing to mutate protected class " + DeepAccessProtectedBase.class.getName();

    @Before
    public void setup() {
        this.deepAccess = new DeepAccess(new GameAccess(), new AllowAllGate(), () -> null);
    }

    /** Sanity: the runtime class passes the guard, the declaring class does not. */
    @Test
    public void fixtureModelsTheLatentGap() {
        assertTrue(ProtectedClasses.isProtected(DeepAccessProtectedBase.class.getName()));
        assertTrue("subclass runtime type must be unprotected",
                !ProtectedClasses.isProtected(UnprotectedSub.class.getName()));
    }

    @Test
    public void setNonFinalFieldOnProtectedDeclaringClassIsDenied() {
        UnprotectedSub obj = new UnprotectedSub();
        try {
            // "secret" is declared on the protected base; VarHandle write path.
            deepAccess.setField(obj, "secret", 999, null);
            fail("write to field declared on protected class must be refused");
        } catch (DeepAccessException e) {
            assertEquals(EXPECTED_REFUSAL, e.getMessage());
        }
    }

    @Test
    public void setFinalFieldOnProtectedDeclaringClassIsDeniedAtUnsafeChoke() {
        UnprotectedSub obj = new UnprotectedSub();
        try {
            // "finalSecret" is a non-constant final on the protected base; Unsafe path.
            deepAccess.setField(obj, "finalSecret", 123, null);
            fail("Unsafe write to final field declared on protected class must be refused");
        } catch (DeepAccessException e) {
            assertEquals(EXPECTED_REFUSAL, e.getMessage());
        }
    }

    @Test
    public void invokeMethodDeclaredOnProtectedClassIsDenied() throws Throwable {
        UnprotectedSub obj = new UnprotectedSub();
        try {
            // "hidden" is declared on the protected base.
            deepAccess.invoke(obj, "hidden", new Class<?>[]{int.class},
                    new Object[]{21}, null);
            fail("invoke of method declared on protected class must be refused");
        } catch (DeepAccessException e) {
            assertEquals(EXPECTED_REFUSAL, e.getMessage());
        }
    }
}
