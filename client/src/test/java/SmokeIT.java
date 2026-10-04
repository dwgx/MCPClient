import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Failsafe integration test (runs in `mvn verify` when -Dsmoke.skip=false).
 *
 * Forks the headless {@link SmokeDriver} in a child JVM — because Minecraft's
 * Main.main takes over the calling thread as the game loop and GLFW must own the
 * main thread, it cannot run inside a JUnit test method. The child exits with a
 * status code (0 = reached in-game world); this test asserts on it.
 *
 * <p><b>Requires: a built shaded jar, the 1.8.9 game assets under test_run/assets, AND the
 * core agent on the fork's command line.</b> The third one was missing until 2026-10-04 and
 * nobody could tell, because a CI runner has neither the jar nor the assets and this test
 * Assume-skips there — so it had been reporting green for as long as it had existed while being
 * unable to pass on any machine.
 *
 * <p><b>Why the agent is a precondition, and why it was the whole failure.</b> With a bare JVM
 * the fork fails inside {@code NetworkSystem.addLocalEndpoint}: Netty 4.2 binds a
 * {@code LocalServerChannel} on an Nio group instead of a {@code LocalIoHandler}-backed one and
 * throws {@code "IoHandle of type LocalServerChannel$LocalServerUnsafe not supported"}. That is
 * KI-4, and the product does not have it: the fix is the bytecode patch
 * {@code Ki4LocalServerChannelPatch}, which is registered by default but only ever applied by
 * {@code -javaagent}, which is exactly what {@code scripts/run-mcp.bat} passes and this fork
 * did not. The patch is pinned headlessly against the real vanilla class by
 * {@code Ki4LocalServerChannelPatchTest}; what this test could have added is the game-level
 * end of it.
 *
 * <p>So the fork is told to arm the agent, and when it cannot the test says which precondition
 * is missing instead of failing on a defect that was fixed two days ago and is unit-tested.
 * See docs/agency/test-census.md section 3 for the measurement behind this text.
 */
public class SmokeIT {

    private static final long TIMEOUT_MS = 180_000L;

    @Test
    public void singlePlayerWorldSmoke() throws Exception {
        runForked("SmokeDriver");
    }

    private void runForked(String mainClass) throws Exception {
        File projectRoot = new File(System.getProperty("user.dir")).getParentFile(); // client/ -> repo root
        File jar = new File(projectRoot, "client/target/MCP-1.8.9.jar");
        File testClasses = new File(projectRoot, "client/target/test-classes");
        // The argfile lives in scripts/, not at the repo root. It was looked up at the root,
        // so `argfile.isFile()` was false on every machine, the fork silently lost every
        // --add-opens / --enable-native-access / -Dfile.encoding line, and nothing said so:
        // `if (argfile.isFile())` is a silent default. Same shape as a test that skips.
        File argfile = new File(projectRoot, "scripts/jvm-args-jdk25.txt");
        // scripts/run-mcp.bat:50 — the product runs on this jar as -javaagent, and KI-4's fix
        // is a bytecode patch that only ever gets applied by that agent.
        File agent = new File(projectRoot, "core/target/core-1.8.9-all.jar");
        File runDir = new File(projectRoot, "test_run");
        File assets = new File(runDir, "assets");

        Assume.assumeTrue("shaded jar not built (run `mvn package` first)", jar.isFile());
        Assume.assumeTrue("game assets missing under test_run/assets — skipping runtime smoke",
            assets.isDirectory());
        Assume.assumeTrue("core agent jar not built (run `mvn -pl core package`); without it "
                + "KI-4 is unpatched and the fork cannot reach a world",
            agent.isFile());
        Assume.assumeTrue("jvm argfile missing at " + argfile, argfile.isFile());

        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String cp = testClasses.getAbsolutePath() + File.pathSeparator + jar.getAbsolutePath();

        List<String> cmd = new ArrayList<String>();
        cmd.add(javaBin);
        cmd.add("@" + argfile.getAbsolutePath());
        cmd.add("-javaagent:" + agent.getAbsolutePath());
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add(mainClass);

        Process p = new ProcessBuilder(cmd)
            .directory(runDir)
            .redirectErrorStream(true)
            .inheritIO()
            .start();

        boolean finished = p.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new AssertionError(mainClass + " did not finish within " + TIMEOUT_MS + "ms");
        }
        assertEquals(mainClass + " should exit 0 (reached in-game world)", 0, p.exitValue());
    }
}
