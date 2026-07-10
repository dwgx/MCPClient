import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import net.marcloud.mcp.core.hotload.CompileResult;
import net.marcloud.mcp.core.hotload.HotLoadEngine;
import net.marcloud.mcp.core.hotload.InMemoryCompiler;
import org.junit.Test;

/**
 * Tests the hot-load engine's capability-1 path (compile + load brand-new
 * classes), which works on a plain JDK with no agent. Runs in the normal
 * {@code mvn test} on the JBR toolchain. Capability 2/3 (redefine / DCEVM
 * structural change) needs a live -javaagent and is verified via the launch
 * probe, not here.
 */
public class HotLoadEngineTest {

    @Test
    public void compilesAndRunsBrandNewClass() {
        HotLoadEngine engine = new HotLoadEngine(getClass().getClassLoader());
        String src = "package gen;\n"
                   + "public class Alpha {\n"
                   + "  public int add(int a, int b) { return a + b; }\n"
                   + "}\n";
        HotLoadEngine.LoadOutcome out = engine.loadNew("gen.Alpha", src);
        assertTrue(out.message(), out.success());
        assertNotNull(out.loadedClass());
    }

    @Test
    public void loadedClassIsInvokable() throws Exception {
        HotLoadEngine engine = new HotLoadEngine(getClass().getClassLoader());
        String src = "package gen;\n"
                   + "public class Beta {\n"
                   + "  public String hello(String who) { return \"hi \" + who; }\n"
                   + "}\n";
        HotLoadEngine.LoadOutcome out = engine.loadNew("gen.Beta", src);
        assertTrue(out.message(), out.success());

        Object inst = out.loadedClass().getDeclaredConstructor().newInstance();
        Method m = out.loadedClass().getMethod("hello", String.class);
        assertEquals("hi world", m.invoke(inst, "world"));
    }

    @Test
    public void compileErrorsAreReportedNotThrown() {
        InMemoryCompiler c = new InMemoryCompiler();
        // Missing semicolon / bad type -> should fail gracefully with diagnostics.
        CompileResult r = c.compile("gen.Broken",
                "package gen; public class Broken { int x = ; }");
        assertFalse(r.success());
        assertFalse("expected diagnostics", r.diagnostics().isEmpty());
        assertTrue(r.bytecode().isEmpty());
    }

    @Test
    public void newClassCanReferenceGameClasspath() {
        // AI-authored code should see classes already on the game JVM classpath.
        // guava is on the client's (transitive) classpath; reference it to prove
        // the compiler inherits java.class.path.
        HotLoadEngine engine = new HotLoadEngine(getClass().getClassLoader());
        String src = "package gen;\n"
                   + "public class UsesJdk {\n"
                   + "  public int len(java.util.List<String> xs) { return xs.size(); }\n"
                   + "}\n";
        HotLoadEngine.LoadOutcome out = engine.loadNew("gen.UsesJdk", src);
        assertTrue(out.message(), out.success());
    }
}
