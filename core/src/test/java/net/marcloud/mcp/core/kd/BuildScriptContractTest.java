package net.marcloud.mcp.core.kd;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Assume;
import org.junit.Test;

/**
 * CONTRACT for the C6 native build script's include-path override.
 *
 * <p>{@code build-clang.sh} compiles {@code core-jvmti.dll} against a JVMTI
 * header dir ({@code JBRINC}). Locally that must default to the {@code _tools}
 * JBR SDK (unchanged posture); CI must be able to point it at the runner JDK's
 * own headers via {@code export JBRINC=$JAVA_HOME/include}. That only works if
 * the assignment is a parameter-default ({@code ${JBRINC:-...}}) rather than a
 * hardcoded string.
 *
 * <p>This reads the script as text (same technique as {@link NativeBridgeContractTest})
 * and asserts BOTH invariants: the env override token is present AND the original
 * {@code _tools} default segment is retained. It FAILS on the pre-change source,
 * whose line had no {@code ${JBRINC:-}.
 */
public class BuildScriptContractTest {

    /** Surefire's cwd is the module dir (core/); the native build script lives here. */
    private static final Path BUILD_SH =
            Path.of("src", "main", "native", "core-jvmti", "build-clang.sh");

    private static String readScript() {
        try {
            return Files.readString(BUILD_SH);
        } catch (IOException e) {
            return null;
        }
    }

    @Test
    public void jbrincIsEnvOverridableWithLocalDefaultRetained() {
        String s = readScript();
        Assume.assumeTrue("build-clang.sh not found from " + BUILD_SH.toAbsolutePath()
                + " (run from the core module dir)", s != null);

        assertTrue("build-clang.sh must make JBRINC env-overridable via a "
                + "parameter-default token `${JBRINC:-` so CI can point it at "
                + "$JAVA_HOME/include (a hardcoded assignment blocks the runner JDK path)",
                s.contains("${JBRINC:-"));

        assertTrue("build-clang.sh must retain the local _tools JBR SDK include as the "
                + "default segment so the local build stays byte-identical when JBRINC is unset",
                s.contains("_tools/jbrsdk-25.0.3-windows-x64-b508.16/include"));
    }
}
