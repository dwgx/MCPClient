package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * No shipped page may offer a button that does nothing.
 *
 * <p>This generalises the rule {@code SettingsOfferNoDeadSwitchesTest} already enforces for one page
 * — "a switch that is present, enabled, and inert tells the user a lie" — to every page under
 * {@code pages/}, which is how the same defect survived one rail row away: {@code PageHome} shipped
 * {@code homePrimary} ("Open kernel") and {@code homeSecondary} ("Reload scene"), both enabled, both
 * accent-styled, both with no {@code onClicked} anywhere in the tree, so {@code FluentButton}'s
 * {@code clicked} signal was emitted into nothing. Neither page-level test looked; this one does.
 *
 * <p><b>Only CLICK-ONLY controls are checked, and that is the dividing line, not an oversight.</b> A
 * toggle, slider or text field holds its own value, so an unwired one still does something the user
 * can see — {@code PageSettings} says so in as many words and ships exactly that (its gamma slider
 * "moves the slider"). A {@code FluentButton} has no state of its own: without a handler a click
 * changes nothing at all, and there is nothing honest left for it to be. Add a type to
 * {@link #CLICK_ONLY} when a control ships that can only be clicked; the test below fails if one of
 * those names stops existing, so the list cannot rot into covering nothing.
 *
 * <p>Source-level, like the guard it generalises, and for the same reason: the failure mode is an
 * author adding a control before anything consumes it, and that must fail on every build rather than
 * only where a display exists. It also means this runs headless on CI, which is where the defect
 * would otherwise ship unnoticed.
 *
 * <p><b>Scope: {@code pages/*.qml}.</b> The shell's own chrome is covered elsewhere — its caption
 * bar by {@code CaptionButtonsLiveIT} — and {@code dwm/Main.qml} is NOT covered. That scene, the
 * single-panel menu the shell does not load, still ships six {@code MenuItem} rows with no
 * {@code onTriggered} anywhere in the tree, so its rows are inert in exactly this way. It is left
 * here deliberately rather than exempted: widening this guard to it is the right follow-up, and it
 * has to arrive with behaviour for those rows (or with the scene's removal), not with an exemption.
 */
public class ShippedPagesOfferNoInertControlsTest {

    private static final Path PAGES = Paths.get("src/main/resources/dwm/pages");
    private static final Path CONTROLS = Paths.get("src/main/resources/dwm/controls");

    /**
     * Controls with no state of their own, so an unwired instance is inert rather than merely
     * unconfigured. Kept as type names rather than inferred: inference from the control sources
     * would be clever, and wrong the first time a control holds a value internally.
     */
    private static final String[] CLICK_ONLY = {"FluentButton"};

    /** What makes a click mean something. */
    private static final Pattern HANDLER = Pattern.compile("onClicked\\s*:");
    /** The documented escape hatch: a control that says it cannot be used. */
    private static final Pattern DISABLED = Pattern.compile("enabled\\s*:\\s*false");

    @Test
    public void everyShippedPageWiresItsClickOnlyControls() throws Exception {
        final List<Path> pages = new ArrayList<Path>();
        Files.walkFileTree(PAGES, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (file.toString().endsWith(".qml")) {
                    pages.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertTrue("no pages found under " + PAGES.toAbsolutePath()
                + " — this guard is covering nothing", !pages.isEmpty());

        StringBuilder offenders = new StringBuilder();
        for (Path page : pages) {
            for (String dead : inertClickOnly(SourceScan.read(page))) {
                offenders.append("\n  ").append(page.getFileName()).append(": ").append(dead);
            }
        }
        assertTrue("a page ships a click-only control that does nothing. Present and enabled, a "
                + "button that answers no click is the same lie as the switches PageSettings "
                + "removed: either wire onClicked to something real, or take the control out "
                + "until something consumes it." + offenders, offenders.length() == 0);
    }

    /**
     * The guard's own list must still describe the controls that ship, or it goes quietly blind.
     */
    @Test
    public void theGuardCoversControlsThatStillExist() throws Exception {
        for (String type : CLICK_ONLY) {
            assertTrue(type + " must still ship as a control, or CLICK_ONLY is covering nothing but "
                    + "its own history: update the list when a control is renamed.",
                    Files.isRegularFile(CONTROLS.resolve(type + ".qml")));
        }
        assertTrue("FluentButton must still declare the clicked signal this guard exists to see "
                + "consumed; without it a handler would be meaningless too.",
                SourceScan.read(CONTROLS.resolve("FluentButton.qml")).contains("signal clicked()"));
    }

    /**
     * The scanner must actually see a dead button, or the clean result above means nothing.
     *
     * <p>Small inline fixtures rather than a shipped page: a page is what the guard protects, so
     * making one deliberately broken would mean shipping the defect to test the guard. These
     * exercise the scanner's three outcomes and its one blind spot.
     */
    @Test
    public void theGuardFlagsADeadButtonAndPassesALiveOne() {
        String dead = "FluentButton {\n"
                + "    objectName: \"homePrimary\"\n"
                + "    text: \"Open kernel\"\n"
                + "    accent: true\n"
                + "}\n";
        assertEquals("a FluentButton with no onClicked must be reported: it has no state of its "
                + "own, so a click does nothing at all", 1, inertClickOnly(dead).size());

        String wired = "FluentButton {\n"
                + "    text: \"Save\"\n"
                + "    onClicked: settings.save()\n"
                + "}\n";
        assertTrue("a wired button must pass", inertClickOnly(wired).isEmpty());

        String disabled = "FluentButton {\n"
                + "    text: \"Install the optional backend first\"\n"
                + "    enabled: false\n"
                + "}\n";
        assertTrue("a disabled button at least tells the truth, so it is allowed",
                inertClickOnly(disabled).isEmpty());

        String commentedOut = "// FluentButton { text: \"gone\" }\nItem { }\n";
        assertTrue("a control named in a comment is not a control",
                inertClickOnly(commentedOut).isEmpty());
    }

    /**
     * The click-only control instances in {@code source} that carry neither a handler nor a
     * {@code enabled: false}.
     *
     * <p>Blocks are taken from the instance's opening brace to its match, so a handler belongs to
     * the control whose braces contain it. A click-only control nested inside another of the same
     * type would report on the outer block only; no shipped page nests them, and the guard fails
     * loudly rather than silently when it cannot tell.
     */
    static List<String> inertClickOnly(String source) {
        String code = SourceScan.stripComments(source);
        List<String> inert = new ArrayList<String>();
        for (String type : CLICK_ONLY) {
            Matcher m = Pattern.compile("\\b" + type + "\\s*\\{").matcher(code);
            while (m.find()) {
                int open = code.indexOf('{', m.start());
                String block = code.substring(open, SourceScan.matchingDelimiter(code, open, '{', '}'));
                if (!HANDLER.matcher(block).find() && !DISABLED.matcher(block).find()) {
                    inert.add(oneLine(block));
                }
            }
        }
        return inert;
    }

    private static String oneLine(String block) {
        return block.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }
}
