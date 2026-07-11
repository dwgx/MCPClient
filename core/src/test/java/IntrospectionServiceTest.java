import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.marcloud.mcp.core.flt.HookInfo;
import net.marcloud.mcp.core.flt.HookSource;
import net.marcloud.mcp.core.cm.ClassDetail;
import net.marcloud.mcp.core.cm.ClassInfo;
import net.marcloud.mcp.core.cm.ClassListing;
import net.marcloud.mcp.core.cm.CmQuery;
import net.marcloud.mcp.core.cm.MethodInfo;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import org.junit.Test;

/**
 * Tests for CmQuery: list_classes, describe_class, find_method,
 * list_hooks, and descriptor generation. Uses the AgentAccess seam for
 * injection (no live agent required).
 */
public class IntrospectionServiceTest {

    @Test
    public void listClassesWithInstrumentationSnapshot() {
        CmQuery svc = new CmQuery(
                getClass().getClassLoader(), List.of());

        ClassListing listing = svc.listClasses(null, null, 200);

        assertTrue("total should include at least Object, String", listing.total() >= 2);
        assertEquals("no filter means matched == total", listing.total(), listing.matched());
        // In this headless suite no -javaagent is loaded, so the source is
        // DETERMINISTICALLY the reflection fallback — assert that exactly (the
        // old "instrumentation OR reflection-fallback" was a tautology). This
        // proves the fallback path actually produces a non-empty listing.
        assertFalse("no agent in test JVM",
                net.marcloud.mcp.core.boot.AgentAccess.isLoaded());
        assertEquals("reflection-fallback", listing.source());
        assertTrue("fallback still enumerates classes", listing.classes().size() > 0);
    }

    @Test
    public void listClassesWithPackageFilter() {
        CmQuery svc = new CmQuery(
                getClass().getClassLoader(), List.of());

        ClassListing listing = svc.listClasses("java.lang", null, 200);

        assertTrue("should match at least String or Object", listing.matched() >= 1);
        for (ClassInfo c : listing.classes()) {
            assertTrue("all results should match package filter",
                    c.name().startsWith("java.lang"));
        }
    }

    @Test
    public void listClassesWithNameFilter() {
        CmQuery svc = new CmQuery(
                getClass().getClassLoader(), List.of());

        ClassListing listing = svc.listClasses(null, "string", 200);

        assertTrue("should match at least String", listing.matched() >= 1);
        for (ClassInfo c : listing.classes()) {
            assertTrue("name filter is case-insensitive",
                    c.name().toLowerCase().contains("string"));
        }
    }

    @Test
    public void listClassesHonorsLimit() {
        CmQuery svc = new CmQuery(
                getClass().getClassLoader(), List.of());

        ClassListing listing = svc.listClasses(null, null, 10);

        assertTrue("shown should be capped at limit", listing.classes().size() <= 10);
    }

    @Test
    public void listClassesFlagsProtectedClass() {
        // Force the class to be loaded so we can test the protected flag
        Class<?> permClass = SeClearancePolicy.class;
        assertNotNull("SeClearancePolicy should be loadable", permClass);

        CmQuery svc = new CmQuery(
                getClass().getClassLoader(), List.of());

        ClassListing listing = svc.listClasses("net.marcloud.mcp.core.security", null, 200);

        boolean foundProtected = false;
        for (ClassInfo c : listing.classes()) {
            if (c.name().equals("net.marcloud.mcp.core.se.SeClearancePolicy")) {
                assertTrue("SeClearancePolicy should be flagged protected", c.protectedClass());
                foundProtected = true;
                break;
            }
        }

        // If we're in fallback mode, the class might not be in the snapshot even though loaded.
        // So test describe_class directly instead.
        if (!foundProtected) {
            ClassDetail d = svc.describeClass("net.marcloud.mcp.core.se.SeClearancePolicy");
            assertTrue("SeClearancePolicy should be flagged protected via describe",
                    d.protectedClass());
        }
    }

    @Test
    public void describeClassForLoadedJdkClass() {
        CmQuery svc = new CmQuery(
                getClass().getClassLoader(), List.of());

        ClassDetail d = svc.describeClass("java.util.ArrayList");

        assertEquals("java.util.ArrayList", d.name());
        assertTrue("kind should contain 'class'", d.kind().contains("class"));
        assertTrue("superclass should end with AbstractList",
                d.superclass() != null && d.superclass().endsWith("AbstractList"));
        assertTrue("interfaces should include java.util.List",
                d.interfaces().stream().anyMatch(i -> i.equals("java.util.List")));
        assertTrue("methods should include 'add'",
                d.methods().stream().anyMatch(m -> m.name().equals("add")));
        assertFalse("ArrayList is not protected", d.protectedClass());
    }

    @Test
    public void describeClassForProtectedCoreClass() {
        CmQuery svc = new CmQuery(
                getClass().getClassLoader(), List.of());

        ClassDetail d = svc.describeClass("net.marcloud.mcp.core.se.SeClearancePolicy");

        assertEquals("net.marcloud.mcp.core.se.SeClearancePolicy", d.name());
        assertTrue("SeClearancePolicy should be flagged protected", d.protectedClass());
    }

    @Test
    public void describeClassForUnresolvedClass() {
        CmQuery svc = new CmQuery(
                getClass().getClassLoader(), List.of());

        ClassDetail d = svc.describeClass("does.not.Exist");

        assertEquals("does.not.Exist", d.name());
        assertEquals("<unresolved>", d.kind());
        assertNotNull("note should explain the failure", d.note());
        assertTrue("note should mention ClassNotFoundException",
                d.note().contains("ClassNotFoundException"));
    }

    @Test
    public void findMethodByName() {
        CmQuery svc = new CmQuery(
                getClass().getClassLoader(), List.of());

        List<MethodInfo> results = svc.findMethod("toString", null, null, 100);

        assertFalse("should find at least one toString method", results.isEmpty());
        for (MethodInfo m : results) {
            assertTrue("all results should contain 'toString'",
                    m.name().toLowerCase().contains("tostring"));
        }
    }

    @Test
    public void findMethodWithOwnerFilter() {
        CmQuery svc = new CmQuery(
                getClass().getClassLoader(), List.of());

        List<MethodInfo> results = svc.findMethod("toString", "java.lang.String", null, 100);

        assertFalse("should find String.toString", results.isEmpty());
        boolean foundString = false;
        for (MethodInfo m : results) {
            if (m.owner().equals("java.lang.String")) {
                foundString = true;
            }
        }
        assertTrue("should have found java.lang.String#toString", foundString);
    }

    @Test
    public void findMethodReturnsEmptyForBlankMethodName() {
        CmQuery svc = new CmQuery(
                getClass().getClassLoader(), List.of());

        List<MethodInfo> results = svc.findMethod("", null, null, 100);

        assertTrue("blank method filter should return empty", results.isEmpty());
    }

    @Test
    public void descriptorOfMethod() throws Exception {
        java.lang.reflect.Method m = String.class.getDeclaredMethod("substring", int.class, int.class);

        String desc = CmQuery.descriptorOf(m);

        assertEquals("(II)Ljava/lang/String;", desc);
    }

    @Test
    public void typeNameForPrimitive() {
        String name = CmQuery.typeName(int.class);

        assertEquals("int", name);
    }

    @Test
    public void typeNameForArray() {
        String name = CmQuery.typeName(int[].class);

        assertEquals("int[]", name);
    }

    @Test
    public void typeNameForObjectArray() {
        String name = CmQuery.typeName(String[].class);

        assertEquals("java.lang.String[]", name);
    }

    @Test
    public void listHooksFromStubSource() {
        List<HookInfo> stubHooks = List.of(
                new HookInfo("net.minecraft.network.NetworkManager", "channelRead0",
                        "NetworkAdvice.ChannelRead0", "bytebuddy-advice-retransform", true),
                new HookInfo("net.minecraft.network.NetworkManager", "sendPacket",
                        "NetworkAdvice.SendPacket", "bytebuddy-advice-retransform", false));

        HookSource stubSource = new HookSource() {
            @Override
            public List<HookInfo> hooks() {
                return stubHooks;
            }
        };

        CmQuery svc = new CmQuery(
                getClass().getClassLoader(), List.of(stubSource));

        List<HookInfo> result = svc.listHooks();

        assertEquals("should return all stub hooks", 2, result.size());
        assertTrue("first hook should be installed", result.get(0).installed());
        assertFalse("second hook should not be installed", result.get(1).installed());
    }

    @Test
    public void listHooksHandlesFailingSource() {
        HookSource failingSource = new HookSource() {
            @Override
            public List<HookInfo> hooks() {
                throw new RuntimeException("simulated failure");
            }
        };

        HookSource goodSource = () -> List.of(
                new HookInfo("test.Class", "testMethod", "TestAdvice", "test", true));

        CmQuery svc = new CmQuery(
                getClass().getClassLoader(), List.of(failingSource, goodSource));

        List<HookInfo> result = svc.listHooks();

        assertEquals("should skip failing source and return good source", 1, result.size());
    }
}
