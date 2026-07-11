import static org.junit.Assert.assertEquals;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Modifier;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.mm.MmAccess;
import net.marcloud.mcp.core.mm.MmAccessException;
import net.marcloud.mcp.core.se.AllowAllGate;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests MmAccess: read/write private fields (incl final), invoke private methods,
 * hierarchy walk, protected-class guard, and cache invalidation. Game-free unit
 * tests on plain POJOs. Tests only the public MmAccess API (doesn't access
 * package-private RootResolver/ValueCodec directly).
 */
public class DeepAccessTest {

    private MmAccess deepAccess;

    @Before
    public void setup() {
        // Dummy GameAccess (tests use static targets, no game objects)
        GameAccess game = new GameAccess();
        this.deepAccess = new MmAccess(game, new AllowAllGate(), () -> null);
    }

    // ===== TEST POJOs =====

    public static class TestPojo {
        private int privateField = 42;
        private final String privateFinalField = "immutable";
        private static int staticField = 100;
        private static final int STATIC_FINAL_NON_CONSTANT = computeConstant();

        private static int computeConstant() {
            return 999;
        }

        private int privateMethod(int a, int b) {
            return a + b;
        }

        private String privateMethodWithString(String s, int n) {
            return s.repeat(n);
        }
    }

    public static class SubPojo extends TestPojo {
        private String subField = "child";
    }

    // ===== TESTS =====

    @Test
    public void readsPrivateInstanceField() {
        TestPojo obj = new TestPojo();
        Object val = deepAccess.getField(obj, "privateField");
        assertEquals(42, val);
    }

    @Test
    public void writesPrivateNonFinalField() {
        TestPojo obj = new TestPojo();
        deepAccess.setField(obj, "privateField", 123, null);
        Object val = deepAccess.getField(obj, "privateField");
        assertEquals(123, val);
    }

    @Test
    public void writesPrivateFinalFieldViaUnsafe() {
        TestPojo obj = new TestPojo();
        // This writes the storage; read-back should see the new value (barring inlining)
        deepAccess.setField(obj, "privateFinalField", "mutated", null);
        Object val = deepAccess.getField(obj, "privateFinalField");
        assertEquals("mutated", val);
    }

    @Test
    public void readsAndWritesStaticField() {
        Object val = deepAccess.getStaticField(TestPojo.class, "staticField");
        assertEquals(100, val);

        deepAccess.setStaticField(TestPojo.class, "staticField", 200, null);
        val = deepAccess.getStaticField(TestPojo.class, "staticField");
        assertEquals(200, val);
    }

    @Test
    public void writesStaticFinalNonConstant() {
        // STATIC_FINAL_NON_CONSTANT is initialized from computeConstant(), not a constant
        Object val = deepAccess.getStaticField(TestPojo.class, "STATIC_FINAL_NON_CONSTANT");
        assertEquals(999, val);

        // Write via Unsafe
        deepAccess.setStaticField(TestPojo.class, "STATIC_FINAL_NON_CONSTANT", 777, null);
        val = deepAccess.getStaticField(TestPojo.class, "STATIC_FINAL_NON_CONSTANT");
        assertEquals(777, val);
    }

    @Test
    public void invokesPrivateMethod() throws Throwable {
        TestPojo obj = new TestPojo();
        Object result = deepAccess.invoke(obj, "privateMethod",
                new Class<?>[]{int.class, int.class},
                new Object[]{10, 20}, null);
        assertEquals(30, result);
    }

    @Test
    public void invokesPrivateMethodWithCoercion() throws Throwable {
        TestPojo obj = new TestPojo();
        // JSON numbers arrive as Integer/Long, coerce to primitives
        Object result = deepAccess.invoke(obj, "privateMethodWithString",
                new Class<?>[]{String.class, int.class},
                new Object[]{"hi", 3}, null);
        assertEquals("hihihi", result);
    }

    @Test
    public void resolvesFieldOnSuperclass() {
        SubPojo obj = new SubPojo();
        // privateField is declared on TestPojo, not SubPojo
        Object val = deepAccess.getField(obj, "privateField");
        assertEquals(42, val);
    }

    @Test
    public void refusesProtectedClass() {
        // SeClearancePolicy is in the protected set
        try {
            deepAccess.setStaticField(
                    net.marcloud.mcp.core.se.SeClearancePolicy.class,
                    "someFakeField", "evil", null);
            fail("should have thrown MmAccessException for protected class");
        } catch (MmAccessException e) {
            assertTrue(e.getMessage().contains("protected"));
        }
    }

    @Test
    public void invalidateClearsCaches() {
        TestPojo obj = new TestPojo();
        // Read to populate cache
        deepAccess.getField(obj, "privateField");
        // Write to populate write cache
        deepAccess.setField(obj, "privateField", 999, null);

        // Invalidate
        deepAccess.invalidate(TestPojo.class);

        // Should still work (re-cache)
        Object val = deepAccess.getField(obj, "privateField");
        assertEquals(999, val);
    }
}
