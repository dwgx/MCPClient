import java.util.concurrent.Callable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.main.Main;
import net.minecraft.client.multiplayer.GuiConnecting;

/**
 * Headless multiplayer smoke test (Phase 6C): boot client, wait for main menu,
 * then programmatically connect to a local vanilla 1.8.9 server at 127.0.0.1:25565.
 * Exercises the full Netty network stack: handshake, login (encryption negotiation
 * — offline server skips actual encryption but runs the login flow), compression,
 * and play packets (join game, chunk data, player spawn).
 *
 * Prereq: a vanilla 1.8.9 server running offline on 127.0.0.1:25565.
 * Run:  java @jvm-args-jdk25.txt -cp <testclasses>;<jar> ServerJoinTest  (cwd = test_run)
 */
public class ServerJoinTest {

    private static final long MENU_TIMEOUT_MS = 60_000L;
    private static final long JOIN_RUN_MS = 20_000L;
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 25565;

    public static void main(String[] args) throws Exception {
        Thread watchdog = new Thread(ServerJoinTest::drive, "ServerJoin-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Main.main(new String[] {
            "--version", "MavenMCP", "--accessToken", "0",
            "--assetsDir", "assets", "--assetIndex", "1.8", "--userProperties", "{}"
        });
    }

    private static void drive() {
        try {
            Minecraft mc = waitForMainMenu();
            if (mc == null) {
                System.out.println("[JOIN] FAIL: main menu not reached");
                Runtime.getRuntime().halt(2);
                return;
            }
            System.out.println("[JOIN] main menu reached; connecting to " + HOST + ":" + PORT + "...");
            final Minecraft client = mc;
            client.addScheduledTask(new Callable<Object>() {
                public Object call() {
                    client.displayGuiScreen(new GuiConnecting(client.currentScreen, client, HOST, PORT));
                    return null;
                }
            });

            long deadline = System.currentTimeMillis() + JOIN_RUN_MS;
            boolean joined = false;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1000L);
                String screen = client.currentScreen == null ? "in-game(null screen)"
                    : client.currentScreen.getClass().getSimpleName();
                if (client.theWorld != null && client.thePlayer != null) {
                    joined = true;
                    System.out.println("[JOIN] connected: player at "
                        + (int) client.thePlayer.posX + "," + (int) client.thePlayer.posY
                        + "," + (int) client.thePlayer.posZ + " screen=" + screen);
                } else {
                    System.out.println("[JOIN] connecting... screen=" + screen);
                }
            }
            System.out.println("[JOIN] " + (joined ? "PASS: joined multiplayer server" : "FAIL: never joined"));
            Runtime.getRuntime().halt(joined ? 0 : 3);
        } catch (Throwable t) {
            System.out.println("[JOIN] EXCEPTION:");
            t.printStackTrace(System.out);
            Runtime.getRuntime().halt(4);
        }
    }

    private static Minecraft waitForMainMenu() throws InterruptedException {
        long deadline = System.currentTimeMillis() + MENU_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.currentScreen != null
                && mc.currentScreen.getClass().getSimpleName().contains("MainMenu")) {
                return mc;
            }
            Thread.sleep(500L);
        }
        return Minecraft.getMinecraft();
    }
}
