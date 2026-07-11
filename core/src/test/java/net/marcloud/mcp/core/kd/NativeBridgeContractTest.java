package net.marcloud.mcp.core.kd;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.junit.Assume;
import org.junit.Test;

/**
 * RENAME/DRIFT SAFETY NET for the C6 native bridge.
 *
 * <p>The JVMTI agent ({@code core-jvmti.c}) binds its native functions onto the
 * Java class {@link KdBridge} via a hardcoded {@code FindClass(...)} FQN string
 * plus a {@code JNINativeMethod} table of name+JNI-descriptor rows. None of that
 * is checked by the compiler: if the Java class moves/renames (as it did in the
 * NT-Executive rename: {@code debug.DebuggerBridge} → {@code kd.KdBridge}) or a
 * native method's signature changes, the C string silently goes stale, the JNI
 * bind fails at load, {@code KdBridge.isAvailable()} is permanently false even
 * with {@code -agentpath}, and nothing reports it — the headless suite never
 * loads the DLL.
 *
 * <p>This test closes that gap WITHOUT needing the DLL: it reads {@code
 * core-jvmti.c} as text and asserts the Java↔native contract by reflection.
 * It FAILS on the pre-fix source (stale {@code debug/DebuggerBridge} FindClass).
 */
public class NativeBridgeContractTest {

    /** Surefire's cwd is the module dir (core/); the native source lives here. */
    private static final Path C_SRC =
            Path.of("src", "main", "native", "core-jvmti", "core-jvmti.c");

    private static String readCSource() {
        try {
            return Files.readString(C_SRC);
        } catch (IOException e) {
            return null;
        }
    }

    /** The JNI type descriptor for one Java type (e.g. int → "I", Thread → "Ljava/lang/Thread;"). */
    private static String jniType(Class<?> t) {
        if (t == boolean.class) return "Z";
        if (t == byte.class) return "B";
        if (t == char.class) return "C";
        if (t == short.class) return "S";
        if (t == int.class) return "I";
        if (t == long.class) return "J";
        if (t == float.class) return "F";
        if (t == double.class) return "D";
        if (t == void.class) return "V";
        if (t.isArray()) return "[" + jniType(t.getComponentType());
        return "L" + t.getName().replace('.', '/') + ";";
    }

    /** The full JNI method descriptor, e.g. "(Ljava/lang/Thread;I)I". */
    private static String jniDescriptor(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(jniType(p));
        }
        return sb.append(")").append(jniType(m.getReturnType())).toString();
    }

    @Test
    public void findClassTargetsCurrentKdBridgeFqn() {
        String c = readCSource();
        Assume.assumeTrue("core-jvmti.c not found from " + C_SRC.toAbsolutePath()
                + " (run from the core module dir)", c != null);

        String expected = "FindClass(e, \"" + KdBridge.class.getName().replace('.', '/') + "\")";
        assertTrue("core-jvmti.c must FindClass the CURRENT KdBridge FQN — expected to contain: "
                + expected + " (a stale FQN here means the JNI bind silently fails at load)",
                c.contains(expected));
    }

    @Test
    public void everyNativeMethodIsBoundWithMatchingDescriptor() {
        String c = readCSource();
        Assume.assumeTrue("core-jvmti.c not found", c != null);

        TreeSet<String> missing = new TreeSet<>();
        int nativeCount = 0;
        for (Method m : KdBridge.class.getDeclaredMethods()) {
            if (!Modifier.isNative(m.getModifiers())) {
                continue;
            }
            nativeCount++;
            // The JNINativeMethod table row is: {"name", "descriptor", ...}
            String row = "\"" + m.getName() + "\"";
            String desc = "\"" + jniDescriptor(m) + "\"";
            if (!c.contains(row)) {
                missing.add(m.getName() + " (name not in bind table)");
            } else if (!c.contains(desc)) {
                missing.add(m.getName() + " descriptor " + jniDescriptor(m) + " (name present, descriptor missing/mismatched)");
            }
        }
        assertTrue("KdBridge must declare native methods", nativeCount > 0);
        assertTrue("native methods not bound (name+descriptor) in core-jvmti.c: " + missing,
                missing.isEmpty());
    }

    @Test
    public void onDebugEventCallbackDescriptorMatches() {
        String c = readCSource();
        Assume.assumeTrue("core-jvmti.c not found", c != null);

        // The agent caches onDebugEvent via GetStaticMethodID(..., "onDebugEvent", "<desc>").
        List<Method> hits = new ArrayList<>();
        for (Method m : KdBridge.class.getDeclaredMethods()) {
            if (m.getName().equals("onDebugEvent")) {
                hits.add(m);
            }
        }
        assertTrue("KdBridge.onDebugEvent must exist", !hits.isEmpty());
        String desc = jniDescriptor(hits.get(0));
        assertTrue("core-jvmti.c must resolve onDebugEvent with descriptor " + desc
                + " (the agent's GetStaticMethodID string must match the Java signature)",
                c.contains("\"onDebugEvent\"") && c.contains("\"" + desc + "\""));
    }
}
