import java.util.concurrent.Callable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.main.Main;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

/**
 * Headless smoke test / debug driver for the LWJGL3 + JDK25 port.
 *
 * Boots the client on a background watchdog, waits until the main menu is up,
 * then programmatically launches a single-player integrated server (exercising
 * the Netty LocalChannel path + world generation + tick), lets it run a few
 * seconds, and reports whether anything threw. Used to verify "in-game" runtime
 * beyond the main menu in an environment with no interactive input.
 *
 * Run:  java @jvm-args-jdk25.txt -cp target/MCP-1.8.9.jar;<testclasses> SmokeTest
 *   (working dir = test_run).  Or via the mvn test-compile output.
 */
public class SmokeTest {

    // How long to wait for the main menu, and how long to run the world.
    private static final long MENU_TIMEOUT_MS = 60_000L;
    private static final long WORLD_RUN_MS = 15_000L;

    public static void main(String[] args) throws Exception {
        Thread watchdog = new Thread(SmokeTest::drive, "SmokeTest-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        // Hand control to the normal client bootstrap on the main thread.
        Main.main(new String[] {
            "--version", "MavenMCP", "--accessToken", "0",
            "--assetsDir", "assets", "--assetIndex", "1.8", "--userProperties", "{}"
        });
    }

    private static void drive() {
        try {
            Minecraft mc = waitForMainMenu();
            if (mc == null) {
                System.out.println("[SMOKE] FAIL: main menu not reached within timeout");
                Runtime.getRuntime().halt(2);
                return;
            }
            System.out.println("[SMOKE] main menu reached; launching integrated world...");

            final Minecraft client = mc;
            client.addScheduledTask(new Callable<Object>() {
                public Object call() {
                    WorldSettings settings = new WorldSettings(
                        System.currentTimeMillis(),
                        WorldSettings.GameType.CREATIVE,
                        true, false, WorldType.DEFAULT);
                    client.launchIntegratedServer("smoke_world", "smoke_world", settings);
                    return null;
                }
            });

            // Let the world generate + tick.
            long deadline = System.currentTimeMillis() + WORLD_RUN_MS;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1000L);
                if (client.theWorld != null && client.thePlayer != null) {
                    System.out.println("[SMOKE] in world: player at "
                        + (int) client.thePlayer.posX + "," + (int) client.thePlayer.posY
                        + "," + (int) client.thePlayer.posZ
                        + " dim=" + client.theWorld.provider.getDimensionId());
                }
            }

            boolean inWorld = client.theWorld != null && client.thePlayer != null;
            System.out.println("[SMOKE] " + (inWorld ? "PASS: reached in-game world" : "FAIL: never entered world"));
            Runtime.getRuntime().halt(inWorld ? 0 : 3);
        } catch (Throwable t) {
            System.out.println("[SMOKE] EXCEPTION in driver:");
            t.printStackTrace(System.out);
            Runtime.getRuntime().halt(4);
        }
    }

    private static Minecraft waitForMainMenu() throws InterruptedException {
        long deadline = System.currentTimeMillis() + MENU_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Minecraft mc = Minecraft.getMinecraft();
            // currentScreen becomes GuiMainMenu once startup finishes.
            if (mc != null && mc.currentScreen != null
                && mc.currentScreen.getClass().getSimpleName().contains("MainMenu")) {
                return mc;
            }
            Thread.sleep(500L);
        }
        return Minecraft.getMinecraft(); // last-ditch: return whatever we have
    }
}
