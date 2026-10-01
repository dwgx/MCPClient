package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;

/**
 * The QML panel must be reachable by the same element table every vanilla menu is reachable by.
 *
 * <p>The gap this closes is not a feature request. {@code core}'s {@code GuiReflect} reflects
 * {@code GuiScreen.buttonList}, and before this an agent could operate every menu in the game
 * and <em>none</em> of ours — the one surface the project itself owns. And there is no other
 * route: {@code dwm} may not import {@code core} (enforced by {@code DwmEntryTest}), and the
 * extractor reads nothing else.
 *
 * <p>The two reference implementations that made this shape obvious rather than clever are
 * KateClient, whose {@code AbstractButton} overrides {@code drawButton} to an empty body
 * (an invisible hit proxy over a self-painted panel), and LiquidLunar, which wraps each module
 * in a real {@code SwitchButton} pushed back into {@code buttonList} every frame.
 *
 * <p><b>Why some of this is source-level.</b> The behaviour that matters most — a human's click
 * and an agent's click reaching the same QML signal — needs a live window and a live scene, and
 * {@code ControlGalleryLiveIT} already proves the scene itself renders and responds. What can be
 * checked headlessly is the shape: that the new classes do not cross the module boundary, that
 * the proxy draws nothing, and that the coordinate conversion runs in one direction only. Those
 * are the three ways this can silently go wrong, so they are the three asserted.
 */
public class QmlElementsArePublishedAsVanillaButtonsTest {

    private static final Path QML_PKG = Paths.get("src/main/java/net/marcloud/mcp/dwm/qml");

    /**
     * File contents with line endings normalised to \n.
     *
     * <p>Several assertions below match a two-line span exactly, because that is what gives them
     * teeth: they distinguish a call that RUNS from one that is written down and guarded. They
     * must not, however, distinguish CRLF from LF -- a `git stash pop` on this very file turned
     * all three red without a line of logic changing. Normalising here keeps the strength and
     * drops the noise.
     */
    private static String read(String file) throws IOException {
        return new String(Files.readAllBytes(QML_PKG.resolve(file)), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    private static String stripComments(String s) {
        // Comments carry the reasoning this test is asserting, so they must not be searched --
        // a file could explain a rule in prose and still break it in code.
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    // ===== the module boundary =====

    @Test
    public void theNewClassesImportNoCoreType() throws Exception {
        for (String f : new String[] {"QmlProxyButton.java", "QmlElementBridge.java",
                "QmlGuiScreen.java", "QmlUiSurface.java"}) {
            String code = stripComments(read(f));
            if (code.contains("net.marcloud.mcp.core")) {
                fail(f + " imports net.marcloud.mcp.core. dwm is a detachable auxiliary module and "
                        + "the only element surface core reads is vanilla's own buttonList; a core "
                        + "import here would close the boundary DwmEntryTest exists to keep open");
            }
        }
    }

    @Test
    public void theProxyOnlyTouchesVanillaAndQml() throws Exception {
        String code = stripComments(read("QmlProxyButton.java"));
        assertTrue("it must extend the vanilla button, since that is the only list core reflects",
                code.contains("extends GuiButton"));
        assertTrue("the dispatch must be the QML signal a human click emits",
                code.contains("clicked.emit()"));
        // There used to be a line here reading
        //     assertTrue("and draw nothing, ...", code.contains("class"));
        // Every Java file contains the substring "class", so it could not fail, and the test
        // right below already checks the real property -- that drawButton's BODY is empty. It was
        // a broken duplicate of a correct assertion two lines down, and it was the only assertion
        // in this cluster that was guaranteed green.
    }

    // ===== the invisible-proxy property =====

    @Test
    public void theProxyDrawsNothingAndPlaysNoSoundOfItsOwn() throws Exception {
        String code = stripComments(read("QmlProxyButton.java"));
        int draw = code.indexOf("public void drawButton");
        assertTrue("drawButton must be overridden: the scene owns every pixel of this screen, and "
                + "a vanilla button drawn over it is a second, differently-styled copy", draw > 0);
        String body = code.substring(draw, code.indexOf("}", draw));
        // The property is that the body is EMPTY, not that it lacks two particular call names.
        // Checking for "drawText" and "drawRect" let `super.drawButton(mc, mouseX, mouseY)`
        // through, which paints the whole button -- the exact failure the assertion's own
        // message names. A mutation that adds that one line kept this test green.
        // stripComments already removed comments, so what is left between the braces is the
        // executable body and nothing else. The property is that it is EMPTY -- not that it
        // lacks two particular call names, which is what let `super.drawButton(mc, mouseX, mouseY)`
        // through, and a super call paints a second copy of every control.
        String statements = body.substring(body.indexOf("{") + 1).trim();
        assertEquals("and the override must be an EMPTY body. Got: " + body, "", statements);
    }

    @Test
    public void aDisabledElementIsPublishedGreyedRatherThanOmitted() throws Exception {
        // An omission is indistinguishable from "not on this page". Seeing the control AND
        // state.enabled=false is strictly more information, and gui_snapshot already reports
        // that field.
        String code = stripComments(read("QmlElementBridge.java"));
        assertTrue("disabled elements must still be published", code.contains("b.enabled = h.enabled"));
        assertTrue("and visible, so the element is in the snapshot at all", code.contains("b.visible = true"));
    }

    // ===== the coordinate conversion =====

    @Test
    public void theConversionRunsInOneDirectionOnly() throws Exception {
        String code = stripComments(read("QmlElementBridge.java"));
        assertTrue("the forward conversion must be present", code.contains("uiScale"));
        // A reverse conversion (scaled-GUI back to QML logical) would be the classic bug the
        // reference clients are still carrying: ClickGui divides by its own scale in four
        // places and leaks it to super. Nothing here should ever need a pixel back.
        assertTrue("a click must resolve by IDENTITY, not by converting a point back to QML "
                + "coordinates -- that reverse conversion is the single largest source of "
                + "off-by-a-few-pixels GUI bugs in this ecosystem, and this design avoids it "
                + "entirely. Found: " + firstOccurrenceOfReverseConversion(code),
                firstOccurrenceOfReverseConversion(code) == null);
    }

    private static String firstOccurrenceOfReverseConversion(String code) {
        // Any division of a scaled/vanilla coordinate back into QML logical units. The forward
        // form multiplies by uiScale and by a W/H ratio; a reverse would divide by one of them
        // while naming neither.
        String[] suspects = {"/ uiScale", "/ uiScale()", "1 / uiScale", "* this.width /",
            "/ this.screenW", "1.0 / sx"};
        for (String s : suspects) {
            if (code.contains(s)) {
                return s;
            }
        }
        return null;
    }

    @Test
    public void rectanglesAreRoundedOutwardSoNoPixelIsLostAtTheEdge() throws Exception {
        String code = stripComments(read("QmlElementBridge.java"));
        assertTrue("x0 must floor", code.contains("Math.floor(ax"));
        assertTrue("x1 must ceil -- rounding inward is how a 3-pixel control becomes 2 and "
                + "stops being clickable near its edge", code.contains("Math.ceil"));
        assertTrue("and a zero-area rectangle must still be published as at least one pixel, or a "
                + "sliver control is invisible rather than merely small",
                code.contains("Math.max(1,"));
    }

    // ===== the human path, which was the asymmetry =====

    @Test
    public void theHumanClickPathIsNotLeftBroken() throws Exception {
        // Without this line a tool could click every published element and a person could not,
        // because GuiActions reflects mouseClicked and calls it directly while a real click
        // never reached here. The worst kind of bug: it works for the automated user only.
        //
        // Asserted as the STATEMENT SHAPE, not as `contains("super.handleMouseInput()")`. The
        // first version of this test did the naive thing, and `if (false) { super
        // .handleMouseInput(); }` passed it -- a presence check cannot tell a call that runs
        // from one that is written down and skipped, which is the only thing that matters here.
        // Requiring the unguarded `try {\n super.handleMouseInput();` form is what gives the
        // assertion teeth, and it is checked against a real mutation below.
        String code = read("QmlGuiScreen.java");
        assertTrue("handleMouseInput must delegate to vanilla UNCONDITIONALLY, or the published "
                        + "buttons are reachable only by a tool. A guarded or commented-out call "
                        + "is exactly the failure this asserts against.",
                code.contains("try {\n            super.handleMouseInput();"));
    }

    /**
     * The publish must happen AFTER layout, not in {@code initGui}.
     *
     * <p>This assertion used to pin the opposite, and the live client overruled it. Publishing
     * from {@code initGui} looks right — that is where vanilla builds its buttons, and it is the
     * only moment the list is both empty and the scene loaded — but a QML layout assigns each
     * child's {@code x}/{@code y} while the scene lays out, and that happens inside
     * {@code surface.frame()}. At {@code initGui} time every child is still at 0,0.
     *
     * <p>What that looked like on the live client: four navigation buttons all publishing
     * {@code {x:12, y:32, w:66, h:20}}. One rectangle, four elements, and a click on any of
     * them hit whichever was drawn last. After moving the publish to the first painted frame
     * they read {@code y = 32, 52, 72, 92}.
     *
     * <p>Kept as a test rather than deleted because the failure is invisible to every other
     * check here: the elements are all still present, all still enabled, and the table is still
     * built. Only their positions are wrong.
     */
    @Test
    public void theTableIsPublishedAfterLayoutRatherThanInInitGui() throws Exception {
        String code = read("QmlGuiScreen.java");
        // The property is "after the first painted frame", NOT "exactly once ever". Those were
        // conflated here, and a live client showed the cost of the conflation: the one-shot rule
        // pinned the agent to the page the panel opened on, so navigating left the published table
        // stale and its rectangles kept answering clicks for controls the panel had left.
        //
        // What must hold: the publish hangs off the FRAME, and it happens after surface.frame()
        // -- because that is where QML assigns each child's x/y, and publishing in initGui yields
        // all-zero positions (which this test's own history records as a real live failure). The
        // frequency is governed separately, by republishIfChanged's difference check.
        int draw = code.indexOf("public void drawScreen");
        int frame = code.indexOf("surface.frame(", draw);
        int publish = code.indexOf("republishIfChanged()", draw);
        assertTrue("the publish must be reachable from drawScreen, and the screen must publish at "
                + "all: " + code, publish > 0);
        assertTrue("and it must come AFTER surface.frame() in that method -- a QML layout assigns "
                + "each child's x/y inside the frame, so publishing before it yields all-zero "
                + "positions, which is a live failure this file already records",
                frame > draw && publish > frame);
        // Isolate initGui and require that it contains no publish AT ALL, wherever the call sits.
        // A first version checked only for one adjacent-line shape, and a mutation that added a
        // republish one line later -- the same defect the live run found, just positioned
        // differently -- sailed through. The invariant is "initGui never publishes", so the
        // assertion has to cover the whole method rather than one layout of two lines.
        int initGuiStart = code.indexOf("public void initGui()");
        int initGuiEnd = code.indexOf("\n    }", initGuiStart);
        assertTrue("could not locate initGui in QmlGuiScreen.java",
                initGuiStart > 0 && initGuiEnd > initGuiStart);
        String initGui = stripComments(code.substring(initGuiStart, initGuiEnd));
        assertTrue("initGui must NOT publish anywhere: it re-loads the scene on every resize and "
                        + "the scene has not been laid out at that point. Found: " + initGui,
                !initGui.contains("republishElements()"));
        // The old assertion here required `publishedAfterLayout = false;` in initGui, on the
        // reasoning that a resize re-loads the scene and the fresh one must not be left
        // unpublished. There is no latch any more, so that literal is unreachable and pinning it
        // would only assert that a deleted line stays deleted. The property it stood for is now
        // carried by the difference check, and it is pinned where it can actually fail: a table
        // collected from a REBUILT tree is not equal to the previous one, because Hit compares
        // the live QML node by identity. See QmlElementNamesAreReadableTest.
        // The clear lives in the bridge, not here: the screen asks for a republish and the
        // bridge owns the list's contents. Asserting it in the wrong file is how a test ends
        // up pinning a line that moved.
        String bridge = read("QmlElementBridge.java");
        // Checked as an ORDER, not as a pasted string. The old form matched
        // bridge.contains("target.clear();\n        for (Hit h : hits) {"), which is broken by
        // whether someone inserts a line between two statements they did not change -- adding the
        // name-uniqueness set cost this suite a red for no behaviour change. The property is that
        // the clear happens, and that it comes before the first add with nothing between them that
        // could return early and skip it.
        int clear = bridge.indexOf("target.clear();");
        int firstAdd = bridge.indexOf("target.add(");
        assertTrue("publish must CLEAR the list, unconditionally -- appending would accumulate a "
                + "duplicate set per resize, and a stale rectangle lets an element from the page "
                + "you left still answer a click: " + bridge, clear > 0);
        assertTrue("and the clear must come before the first add", firstAdd > clear);
    }

    @Test
    public void aFailedPublishLeavesAWorkingScreenRatherThanThrowing() throws Exception {
        String code = read("QmlGuiScreen.java");
        assertTrue("a diagnostic must never take the UI down with it; an empty element table is "
                + "still a usable screen", code.contains("element table publish failed"));
        assertTrue("and the catch must empty the list so a half-built table is not left behind",
                code.contains("buttonList.clear();\n            System.err.println"));
    }
}
