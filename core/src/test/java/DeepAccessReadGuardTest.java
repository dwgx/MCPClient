import static org.junit.Assert.assertEquals;

import net.marcloud.mcp.core.mm.MutateStateTools;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.mm.MmAccess;
import net.marcloud.mcp.core.mm.MmAccessException;
import net.marcloud.mcp.core.se.AllowAllGate;
import net.marcloud.mcp.core.se.DeepAccessProtectedBase;
import net.marcloud.mcp.core.se.SeProtectedObjects;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression test for GAP-9: the C5 READ paths ({@code getField} /
 * {@code getStaticField}) must apply the {@link SeProtectedObjects} guard on both
 * the start class AND the resolved DECLARING (super)class, symmetric with the
 * write/invoke paths. Before the fix the read paths had NO {@code guardProtected}
 * call at all; the only read protection was
 * {@code MutateStateTools.isProtectedValue}, which keys off the RETURNED VALUE's
 * runtime type — so a scalar/String field DECLARED on a protected security class
 * (its value type looks innocuous) sailed straight through.
 *
 * <p>{@link UnprotectedReadSub} is a runtime class whose own FQN is NOT protected,
 * so it passes the start-class check. Its inherited fields are declared on
 * {@link DeepAccessProtectedBase} (under {@code net.marcloud.mcp.core.security.*},
 * hence protected). The field {@code secret} is a plain {@code int} whose value
 * (7) is innocuous — value-type inspection would never flag it — so this is the
 * precise asymmetry GAP-9 describes.
 *
 * <p><b>Non-vacuous.</b> Against the OLD read paths every call returned the field
 * value with no throw (the read paths were unguarded), so each {@code fail(...)}
 * would trip. The gate is {@link AllowAllGate}, so the ONLY thing under test is
 * the SeProtectedObjects guard, not any capability check. Matching the exact
 * declaring-class refusal string (not the loose substring "protected", which the
 * runtime type name {@code UnprotectedReadSub} does not even contain) proves the
 * guard fired on the declaring class rather than some unrelated reflection error.
 */
public class DeepAccessReadGuardTest {

    private MmAccess deepAccess;

    /** Runtime class is NOT protected; the class declaring its fields IS. */
    public static class UnprotectedReadSub extends DeepAccessProtectedBase {
    }

    /**
     * The refusal the declaring-class guard emits for the protected base. Only
     * {@code guardProtected(DeepAccessProtectedBase.class)} produces this exact
     * message.
     */
    private static final String EXPECTED_REFUSAL =
            "refusing to mutate protected class " + DeepAccessProtectedBase.class.getName();

    @Before
    public void setup() {
        this.deepAccess = new MmAccess(new GameAccess(), new AllowAllGate(), () -> null);
    }

    /** Sanity: runtime subclass passes the guard, its declaring base does not. */
    @Test
    public void fixtureModelsTheLatentReadGap() {
        assertTrue(SeProtectedObjects.isProtected(DeepAccessProtectedBase.class.getName()));
        assertTrue("subclass runtime type must be unprotected",
                !SeProtectedObjects.isProtected(UnprotectedReadSub.class.getName()));
    }

    @Test
    public void readInstanceFieldDeclaredOnProtectedClassIsDenied() {
        UnprotectedReadSub obj = new UnprotectedReadSub();
        try {
            // "secret" is a plain int (value 7) declared on the protected base.
            // Its value type is innocuous, so only a guard on the DECLARING class
            // can catch it. Old read path: no guard -> returned 7.
            deepAccess.getField(obj, "secret");
            fail("read of field declared on protected class must be refused");
        } catch (MmAccessException e) {
            assertEquals(EXPECTED_REFUSAL, e.getMessage());
        }
    }

    @Test
    public void readFinalFieldDeclaredOnProtectedClassIsDenied() {
        UnprotectedReadSub obj = new UnprotectedReadSub();
        try {
            // "finalSecret" is a non-constant final int on the protected base.
            deepAccess.getField(obj, "finalSecret");
            fail("read of final field declared on protected class must be refused");
        } catch (MmAccessException e) {
            assertEquals(EXPECTED_REFUSAL, e.getMessage());
        }
    }

    @Test
    public void readStaticFieldOnProtectedClassIsDenied() {
        try {
            // getStaticField's owner-class guard was also absent on the old read
            // path, so a static read against a protected class went through.
            deepAccess.getStaticField(DeepAccessProtectedBase.class, "anyFieldName");
            fail("static read on protected class must be refused");
        } catch (MmAccessException e) {
            assertEquals("refusing to mutate protected class "
                    + DeepAccessProtectedBase.class.getName(), e.getMessage());
        }
    }
}
