package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.junit.Test;

/**
 * Dismissing the screen must release its surface, and nothing may promise otherwise.
 *
 * <p>The defect this locks down: the caption bar's minimised button kept the Skia surface alive so
 * the next open would be "instant" and the UI would be where the user left it. Nothing in this tree
 * could ever re-open it. The only production opener ({@code DwmHotkey} through
 * {@code DwmEntry.createScreen}) builds a NEW screen for every press and Minecraft drops the old
 * instance, so the kept surface was unreachable from the moment the screen went away: never
 * rendered, never re-shown, never closed — and a whole {@code DirectContext} stranded behind it,
 * while the button behaved exactly like close from the user's side.
 *
 * <p><b>Source-level on purpose, and that is a weakness worth naming.</b> The property is "no
 * resource survives a dismissal", and no API reports it: nothing observable through the SPI says "a
 * Skia context is still alive", and the state cannot even be built here — it needs a live GL context
 * and a Minecraft instance to dismiss a screen, which is why no behavioural test can run on the
 * unit-test side (and a live IT would self-skip on CI, i.e. exactly where the regression has to be
 * caught). So this asserts the two checkable halves: the release is unconditional, and the retention
 * flag and the verb that promised it are gone. A future editor who re-adds either gets a red test
 * naming the reason it cannot work.
 *
 * <p>Written as the general form of what {@code SettingsOfferNoDeadSwitchesTest} does for one page:
 * a control that cannot do what it advertises is a defect, not a placeholder.
 */
public class DismissReleasesTheSurfaceTest {

    private static final Path MAIN = Paths.get("src/main");
    private static final Path SCREEN =
            MAIN.resolve("java/net/marcloud/mcp/dwm/qml/QmlGuiScreen.java");
    private static final Path HOST = MAIN.resolve("java/net/marcloud/mcp/dwm/ui/UiWindowHost.java");
    private static final Path COMMANDS =
            MAIN.resolve("java/net/marcloud/mcp/dwm/qml/WindowCommands.java");
    private static final Path SCENES = MAIN.resolve("resources/dwm");

    /**
     * Every dismissal releases the surface.
     *
     * <p>Escape, the caption's close, the host swapping screens: one unconditional call, because a
     * scene nobody is going to reopen is a GPU surface and a Skia context held for nothing.
     */
    @Test
    public void onGuiClosedReleasesTheSurfaceUnconditionally() throws Exception {
        String body = SourceScan.bodyOf(SourceScan.read(SCREEN), "onGuiClosed");

        assertTrue("onGuiClosed must release the surface; its body was: " + body,
                body.contains("surface.close();"));
        assertFalse("onGuiClosed must not release CONDITIONALLY: there is no re-open path in this "
                + "tree that a retained surface could serve (every open builds a new screen), so a "
                + "guard here would strand the Skia context instead of merely keeping it. Body was: "
                + body, body.contains("if "));
    }

    /**
     * The retention flag itself must not come back without a re-open path to justify it.
     */
    @Test
    public void noDismissalRetainsTheSurface() throws Exception {
        assertFalse("QmlGuiScreen must not keep a surface past a dismissal. keepSurfaceOnClose was "
                + "that mechanism: it skipped surface.close() for a reopen nothing could perform, "
                + "so the surface and its DirectContext leaked invisibly. If a screen cache is ever "
                + "added, deleting this assertion is part of that change -- with the cache, not "
                + "instead of it.", SourceScan.read(SCREEN).contains("keepSurfaceOnClose"));
    }

    /**
     * Nor may a verb offer a retention the host cannot honour.
     *
     * <p>Checked as code, not prose: the files explain in comments WHY a minimise verb is gone, and
     * a javadoc naming it is not a declaration. A declaration or a call carries the parenthesis.
     */
    @Test
    public void nothingShipsAMinimiseVerb() throws Exception {
        assertFalse("UiWindowHost must not declare a minimise verb: its only implementation could "
                + "answer it by dismissing the screen, which is what close already does -- see the "
                + "interface's own javadoc.",
                SourceScan.stripComments(SourceScan.read(HOST)).contains("minimize("));
        assertFalse("WindowCommands must not forward a minimise verb.",
                SourceScan.stripComments(SourceScan.read(COMMANDS)).contains("minimize("));
        assertFalse("QmlGuiScreen must not implement one.",
                SourceScan.stripComments(SourceScan.read(SCREEN)).contains("minimize("));

        final StringBuilder offenders = new StringBuilder();
        Files.walkFileTree(SCENES, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (file.toString().endsWith(".qml")
                        && SourceScan.stripComments(SourceScan.read(file)).contains("minimize(")) {
                    offenders.append("\n  ").append(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertTrue("no shipped scene may send a minimise verb: the two caption buttons would then "
                + "do the same thing while claiming to be different, and a window that cannot be "
                + "restored must not advertise that it can." + offenders, offenders.length() == 0);
    }
}
