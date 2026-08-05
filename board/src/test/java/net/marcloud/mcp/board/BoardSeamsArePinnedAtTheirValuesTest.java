package net.marcloud.mcp.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.marcloud.mcp.board.chips.FpsMeterChip;
import net.marcloud.mcp.board.chips.FullbrightChip;
import net.marcloud.mcp.board.chips.OfficialChips;
import net.marcloud.mcp.board.hud.Panel;
import net.marcloud.mcp.board.link.BoardPort;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Value pins for board seams whose CONTRACT IS THE VALUE, not the shape.
 *
 * <p>Every assertion here exists because the surrounding suite already covers the
 * shape and therefore cannot see the value move: it reads a constant symbolically
 * (so it agrees with whatever the constant becomes), asserts only a sign or a
 * non-null (so any plausible-looking substitute passes), or asserts a branch that
 * a headless fixture never reaches. Each of those is a seam that breaks silently
 * at runtime and cannot break at compile time, which is exactly the failure mode
 * that reaches a live game instead of a build.
 *
 * <p>Deliberately literal: where a value is a cross-module agreement, the literal
 * is spelled out rather than read off the constant, because reading the constant
 * is what made the existing coverage blind.
 */
public class BoardSeamsArePinnedAtTheirValuesTest {

    private static final String MC_CLASS = "net.minecraft.client.Minecraft";
    private static final String GAME_SETTINGS_CLASS = "net.minecraft.client.settings.GameSettings";

    /** The gamma FullbrightChip writes while enabled (its private BRIGHT). */
    private static final float FULLBRIGHT_GAMMA = 100.0f;
    /** A stand-in for the player's own gamma; must differ from FULLBRIGHT_GAMMA. */
    private static final float PLAYER_GAMMA = 0.5f;

    private String savedOfficialChips;
    private int savedDebugFps;
    private boolean debugFpsTouched;
    private boolean gameStubInstalled;

    @Before
    public void isolateProcessWideState() {
        // The opt-out property and the game singleton are process-wide, and Board's
        // singletons outlive a single test method, so snapshot before touching them.
        savedOfficialChips = System.getProperty(OfficialChips.PROPERTY);
        System.clearProperty(OfficialChips.PROPERTY);
    }

    @After
    public void restoreProcessWideState() throws Exception {
        // Order matters: drop the game stub BEFORE Board.shutdown() so no chip
        // disable path can observe a half-torn-down game.
        if (gameStubInstalled) {
            uninstallGameStub();
            gameStubInstalled = false;
        }
        if (debugFpsTouched) {
            writeDebugFps(savedDebugFps);
            debugFpsTouched = false;
        }
        Board.shutdown();
        Backplane.clear();
        if (savedOfficialChips == null) {
            System.clearProperty(OfficialChips.PROPERTY);
        } else {
            System.setProperty(OfficialChips.PROPERTY, savedOfficialChips);
        }
    }

    /**
     * The Backplane key is a CROSS-MODULE AGREEMENT, so it is asserted as a literal.
     *
     * <p>The other half of this agreement lives in mcp-core, which hardcodes the same
     * string as its own private constant and cannot be imported from here (board has
     * zero hard dependency on core, by design):
     * {@code core/src/main/java/net/marcloud/mcp/core/link/BoardTraceLink.java}
     * ({@code BOARD_PORT_KEY}) and
     * {@code core/src/main/java/net/marcloud/mcp/core/link/BoardClockBridge.java}
     * ({@code BOARD_PORT_KEY}). Core reaches board only through
     * {@code Class.forName("net.marcloud.mcp.board.Backplane")} plus
     * {@code find("board.port")}, so nothing about this pairing is checked by any
     * compiler in either direction. Editing this literal without editing both core
     * files makes core's lookup return null forever: chat veto, packet veto, the
     * world-signal fan-out and the tick fan-out to board all go quietly dead, with
     * no exception and no log, because core treats a null port as "board absent".
     * If this assertion fails, fix the two core files in the same commit.
     */
    @Test
    public void backplaneKeyIsTheExactStringCoreHardcodes() {
        assertEquals("core discovers board by this exact literal and has no fallback;"
                + " changing it silently disables every core-to-board signal at runtime",
                "board.port", BoardPort.KEY);
    }

    /**
     * Board must be reachable UNDER THAT KEY with no alias fallback. The existing
     * coverage looks up the key and then retries the bare {@code "board"} alias,
     * which Board.init() also registers, so a key that no longer matches core's
     * still resolves and the miss is invisible.
     */
    @Test
    public void initPublishesThePortUnderTheKeyWithNoAliasFallback() throws Exception {
        Board.init();

        Object port = Backplane.find("board.port");
        assertNotNull("core looks up ONLY \"board.port\"; the \"board\" alias is board's"
                + " own convenience and cannot rescue core's lookup", port);
        // Drive it exactly as core does: reflection, no board type on the caller side.
        assertEquals("the entry under this key must be the board port itself; anything"
                + " else and core's reflected id/isStarted probe fails",
                "board", port.getClass().getMethod("id").invoke(port));
        assertSame("the keyed entry must be the live port, not a stale duplicate",
                port, Backplane.find(BoardPort.KEY));

        // Positive counterpart to the negative below: while started, the key resolves.
        assertTrue("a started board must be discoverable under the literal key",
                Backplane.has("board.port"));
        Board.shutdown();
        assertNull("a stale port left under the key after shutdown would let core"
                + " publish into a dead board", Backplane.find("board.port"));
    }

    /**
     * The board-side half of the accessor NAME core reflects on the port.
     *
     * <p>Core's {@code BoardTraceLink.resolveTrace()} calls {@code port.trace()} and
     * then hunts a single-arg {@code publish} on whatever came back. {@code trace()}
     * and {@code features()} are both {@code Object}-returning accessors on the same
     * port, so swapping one for the other compiles cleanly on core's side and fails
     * only at runtime, silently, because the Matrix has no publish method and core
     * reads a null publish as "board absent".
     *
     * <p>This pins the asymmetry core depends on. It cannot turn core's own copy of
     * that method name red from here: core is not on board's test classpath, so the
     * kill for a swapped name has to live beside core's link.
     */
    @Test
    public void onlyTraceCarriesPublishSoTheTwoAccessorsAreNotInterchangeable() throws Exception {
        Board.init();
        Object port = Backplane.find("board.port");
        assertNotNull("without a port under core's key there is nothing to reflect on", port);

        Object trace = port.getClass().getMethod("trace").invoke(port);
        assertSame("core must land on the live bus, not a copy", Board.trace(), trace);
        assertNotNull("trace() is the accessor that carries publish; without it core's"
                + " whole fan-out into board is a no-op", singleArgPublishOf(trace));

        Object features = port.getClass().getMethod("features").invoke(port);
        assertSame("features() is the OTHER Object-returning accessor and must hand back"
                + " the live roster, so what separates the two is capability, not type",
                Board.features(), features);
        assertNull("features() carries no publish, so a peer that reflects the wrong"
                + " accessor name degrades to a permanent silent no-op rather than throwing",
                singleArgPublishOf(features));
    }

    /**
     * CENTER anchoring must keep the offset term. The existing center test sets the
     * offset to (0, 0), so the term contributes nothing and the assertion holds with
     * or without it: a centred watermark or crosshair silently becomes unmovable.
     */
    @Test
    public void centerAnchorAddsTheOffsetOnBothAxes() {
        SizedPanel panel = new SizedPanel(40, 20);
        panel.setAnchor(Panel.Anchor.CENTER);
        panel.setOffset(7, -3);
        panel.resolve(200, 100);

        assertEquals("a centred panel must be nudged horizontally by offsetX",
                (200 - 40) / 2 + 7, panel.resolvedX());
        assertEquals("a centred panel must be nudged vertically by offsetY",
                (100 - 20) / 2 - 3, panel.resolvedY());

        // Distinct nonzero offsets on distinct axes, so a dropped term or a swapped
        // pair cannot coincide with the dead-centre answer.
        SizedPanel centred = new SizedPanel(40, 20);
        centred.setAnchor(Panel.Anchor.CENTER);
        centred.setOffset(0, 0);
        centred.resolve(200, 100);
        assertFalse("offset (7,-3) must not resolve to the same pixel as offset (0,0)",
                centred.resolvedX() == panel.resolvedX()
                        && centred.resolvedY() == panel.resolvedY());
    }

    /**
     * fps() must report the counter's actual value, not merely prove the reflection
     * succeeded. Headless the counter sits at its initial 0, so the existing
     * {@code fps() >= 0} assertion is satisfied by any non-negative constant, and a
     * reading frozen at a plausible 0 in a live game is indistinguishable from a real
     * one, which is precisely what the -1 sentinel exists to make distinguishable.
     */
    @Test
    public void fpsReportsTheCounterValueAndTracksItChanging() throws Exception {
        FpsMeterChip chip = new FpsMeterChip();
        chip.setEnabled(true);

        // Write the counter the game itself writes each frame (Minecraft.debugFPS,
        // read back through the public getDebugFPS accessor the chip reflects).
        writeDebugFps(137);
        assertEquals("fps() must return the frame counter's real value, not a constant"
                + " that only happens to be non-negative", 137, chip.fps());

        writeDebugFps(58);
        assertEquals("fps() must re-read the counter every call, so a changing frame"
                + " rate is visible rather than frozen at a plausible value",
                58, chip.fps());
    }

    /**
     * Disabling fullbright must restore the CAPTURED gamma, which is only observable
     * once the enable actually reached a game. Headless, {@code appliedToGame} is
     * false and the restore branch never runs, so the reversibility the chip promises
     * is asserted by method name only: a restore that re-applied the bright value
     * would leave the client pinned at max brightness forever, with savedGamma dead.
     */
    @Test
    public void disableRestoresTheCapturedGammaNotTheBrightOne() throws Exception {
        installGameStub(PLAYER_GAMMA);
        FullbrightChip chip = new FullbrightChip();

        chip.setEnabled(true);
        assertTrue("the stub game must be reachable, otherwise the restore branch is"
                + " skipped and this test proves nothing", chip.appliedToGame());
        assertEquals("enable must brighten the live gamma", FULLBRIGHT_GAMMA, readGamma(), 0.0001f);

        chip.setEnabled(false);
        assertEquals("disable must put back the gamma captured on enable; re-applying"
                + " the bright value leaves the client permanently at max brightness",
                PLAYER_GAMMA, readGamma(), 0.0001f);
        assertFalse("a restored chip must stop claiming it holds the game's gamma,"
                + " or a later disable would write a stale value", chip.appliedToGame());
    }

    /**
     * All FOUR documented opt-out values must opt out. Only {@code "false"} and
     * {@code "none"} are covered elsewhere; {@code "off"} and {@code "0"} exist
     * nowhere but the javadoc, so the operator who launches with
     * {@code -Dmcp.board.officialChips=off} would get the full roster installed AND
     * enabled, the exact opposite of the instruction, with nothing to notice.
     */
    @Test
    public void everyDocumentedOptOutValueActuallyOptsOut() {
        for (String optOut : new String[] {"false", "none", "off", "0", "OFF", "None", " off "}) {
            System.setProperty(OfficialChips.PROPERTY, optOut);
            assertFalse("\"" + optOut + "\" is a documented opt-out (case-insensitive,"
                    + " trimmed); honouring only some of them installs a roster the"
                    + " operator explicitly asked not to have", OfficialChips.enabled());

            Matrix<Chip> matrix = new Matrix<Chip>();
            assertEquals("opt-out must install nothing", 0,
                    OfficialChips.install(matrix, new Trace()));
            assertEquals("opt-out must leave a bare board", 0, matrix.size());
        }
    }

    /**
     * The positive counterpart: the opt-out set is exactly those four values, so a
     * value merely NEAR one of them must not opt out. Without this, widening the set
     * (or a typo that happens to match something) would pass unnoticed.
     */
    @Test
    public void valuesOutsideTheOptOutSetStillInstallTheRoster() {
        for (String on : new String[] {"offf", "of", "true", "1", "00", ""}) {
            System.setProperty(OfficialChips.PROPERTY, on);
            assertTrue("\"" + on + "\" is not a documented opt-out, so the roster must"
                    + " still install; default-on is the contract", OfficialChips.enabled());
        }
        System.clearProperty(OfficialChips.PROPERTY);
        assertTrue("unset means default-on", OfficialChips.enabled());
        Matrix<Chip> matrix = new Matrix<Chip>();
        assertEquals("default-on installs the full roster", 5,
                OfficialChips.install(matrix, new Trace()));
    }

    // ---- helpers ------------------------------------------------------------

    /** Core's own resolution rule: a single-arg method named publish, or null. */
    private static Method singleArgPublishOf(Object target) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals("publish") && m.getParameterTypes().length == 1) {
                return m;
            }
        }
        return null;
    }

    /**
     * Write the frame counter the game writes each frame. Reflective and private-field
     * deep because the chip deliberately reads the PUBLIC accessor over the private
     * backing field, and the accessor is the only thing a test may pin.
     */
    private void writeDebugFps(int value) throws Exception {
        Field debugFps = Class.forName(MC_CLASS).getDeclaredField("debugFPS");
        debugFps.setAccessible(true);
        if (!debugFpsTouched) {
            savedDebugFps = debugFps.getInt(null);
            debugFpsTouched = true;
        }
        debugFps.setInt(null, value);
    }

    /**
     * Stand up the minimum game the gamma seam touches: a Minecraft holding a
     * GameSettings with the given gamma, installed as the static singleton the chip
     * resolves through.
     *
     * <p>Both objects are allocated WITHOUT running their constructors. Minecraft's
     * constructor boots an IntegratedServer, an authentication service and the
     * resource-pack stack, none of which can run in a unit test, and none of which
     * this seam reads: the chip's whole reach into the game is
     * {@code getMinecraft().gameSettings.gammaSetting}, so those two fields ARE the
     * game as far as fullbright is concerned. A stub is what makes the restore branch
     * reachable at all; without it the branch is unasserted no matter how the test is
     * named.
     */
    private void installGameStub(float gamma) throws Exception {
        Class<?> mc = Class.forName(MC_CLASS);
        Object game = allocateWithoutConstructor(mc);
        Object settings = allocateWithoutConstructor(Class.forName(GAME_SETTINGS_CLASS));
        settings.getClass().getField("gammaSetting").setFloat(settings, gamma);
        mc.getField("gameSettings").set(game, settings);
        singletonField().set(null, game);
        gameStubInstalled = true;
    }

    /**
     * Put the singleton back to absent. Mandatory: the rest of the suite asserts the
     * headless contract (game absent means every game-touching path is a no-op), and
     * surefire reuses this JVM, so a leaked stub would silently rewrite what those
     * tests are testing.
     */
    private void uninstallGameStub() throws Exception {
        singletonField().set(null, null);
    }

    private float readGamma() throws Exception {
        Class<?> mc = Class.forName(MC_CLASS);
        Object game = mc.getMethod("getMinecraft").invoke(null);
        Object settings = mc.getField("gameSettings").get(game);
        return settings.getClass().getField("gammaSetting").getFloat(settings);
    }

    private static Field singletonField() throws Exception {
        Field singleton = Class.forName(MC_CLASS).getDeclaredField("theMinecraft");
        singleton.setAccessible(true);
        return singleton;
    }

    /**
     * Allocate without running a constructor, because there is no seam to stub instead:
     * {@code FullbrightChip.setGamma} reaches the game through {@code Class.forName} on the
     * vanilla class name, so the restore branch is reachable only if the real
     * {@code theMinecraft} singleton holds an object with a readable {@code gammaSetting}.
     * A real {@code Minecraft} cannot be built headless, so the choice is this or leaving
     * {@code onDisable}'s restore permanently unasserted.
     *
     * <p><b>When this breaks, it is the mechanism and NOT FullbrightChip.</b>
     * {@code sun.misc.Unsafe::allocateInstance} is terminally deprecated and scheduled for
     * removal; on the JDK where it goes, these two gamma tests will error out here while the
     * production code is untouched. That distinction is written down because this repo keeps
     * paying for the opposite mistake -- a failing harness that reads like a regression in the
     * code under test (see the eight false reds a dead player caused,
     * handoff-2026-08-06 section 3(6)). If you are reading this after the removal: the fix is a
     * seam in FullbrightChip, not a cleverer allocation.
     */
    private static Object allocateWithoutConstructor(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(theUnsafe.get(null), type);
    }

    /** A Panel with a fixed declared size, so anchor arithmetic is exact. */
    private static final class SizedPanel extends Panel {
        SizedPanel(int width, int height) {
            setSize(width, height);
        }
    }
}
