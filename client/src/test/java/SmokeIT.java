import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Failsafe integration test (runs in `mvn verify` when -Dsmoke.skip=false).
 *
 * Forks the headless {@link SmokeTest} in a child JVM — because Minecraft's
 * Main.main takes over the calling thread as the game loop and GLFW must own the
 * main thread, it cannot run inside a JUnit test method. The child exits with a
 * status code (0 = reached in-game world); this test asserts on it.
 *
 * Requires: a built shaded jar and the 1.8.9 game assets under test_run/assets.
 * If either is missing (e.g. a CI runner without assets), the test is skipped
 * via Assume rather than failed.
 */
public class SmokeIT {

    private static final long TIMEOUT_MS = 180_000L;

    @Test
    public void singlePlayerWorldSmoke() throws Exception {
        runForked("SmokeTest");
    }

    private void runForked(String mainClass) throws Exception {
        File projectRoot = new File(System.getProperty("user.dir")).getParentFile(); // client/ -> repo root
        File jar = new File(projectRoot, "client/target/MCP-1.8.9.jar");
        File testClasses = new File(projectRoot, "client/target/test-classes");
        File argfile = new File(projectRoot, "jvm-args-jdk25.txt");
        File runDir = new File(projectRoot, "test_run");
        File assets = new File(runDir, "assets");

        Assume.assumeTrue("shaded jar not built (run `mvn package` first)", jar.isFile());
        Assume.assumeTrue("game assets missing under test_run/assets — skipping runtime smoke",
            assets.isDirectory());

        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String cp = testClasses.getAbsolutePath() + File.pathSeparator + jar.getAbsolutePath();

        List<String> cmd = new ArrayList<String>();
        cmd.add(javaBin);
        if (argfile.isFile()) {
            cmd.add("@" + argfile.getAbsolutePath());
        }
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
