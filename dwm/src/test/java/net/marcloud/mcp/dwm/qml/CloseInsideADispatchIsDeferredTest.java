package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * The surface must not be disposed while a dispatch into it is still on the stack.
 *
 * <p>The defect this locks down is a use-after-dispose on the primary dismissal path. Clicking
 * the caption's close button runs, all inside a single {@code view.dispatchPointerUp}:
 *
 * <pre>
 *   QML onClicked -&gt; WindowCommands.close() -&gt; QmlGuiScreen.close()
 *     -&gt; mc.displayGuiScreen(null) -&gt; Minecraft calls onGuiClosed()
 *     -&gt; QmlGuiScreen.onGuiClosed() -&gt; surface.close() -&gt; view.dispose()
 * </pre>
 *
 * ...and control then unwinds back into the {@code QmlView} that was just disposed, with qml4j
 * still owing it a return from the handler it is inside. The guard in {@code dispatch} could not
 * catch it: it tests {@code open} <em>before</em> {@code call.run()}, and {@code close()} only
 * clears {@code open} after the dispose has already happened. This is the same class of native
 * fault that crashed a live client twice, on the one button a user is most likely to press.
 *
 * <p><b>Source-level, and that is a real limitation worth stating plainly</b> rather than hiding:
 * the property is about call ordering across qml4j's Java/native boundary, and proving it
 * behaviourally needs a live GL context plus a real click, which is a live IT that self-skips on
 * CI — exactly where the regression has to be caught. So this pins the three decisions the fix is
 * made of, each of which the revert would remove: {@code close()} defers while a dispatch is in
 * flight, {@code dispatch()} brackets the call and releases on the way out, and the release is in
 * a {@code finally} so a faulting handler still frees the natives instead of leaking them.
 *
 * <p>A guard that only asserted "the word dispatchDepth appears" would pass on a field nobody
 * ever incremented, so each assertion names the specific shape that does the work.
 */
public class CloseInsideADispatchIsDeferredTest {

    private static final Path SURFACE = Paths.get(
            "src/main/java/net/marcloud/mcp/dwm/qml/QmlUiSurface.java");

    @Test
    public void closeDefersWhileADispatchIsInFlightInsteadOfDisposingUnderneathIt() throws Exception {
        String body = SourceScan.bodyOf(SourceScan.read(SURFACE), "close");

        assertTrue("close() must consult the dispatch depth before releasing: the caption's X "
                + "reaches close() from inside view.dispatchPointerUp, and disposing there frees "
                + "the object the call is about to resume in. Body was: " + body,
                body.contains("dispatchDepth"));
        assertTrue("the deferred close must be recorded, not dropped, or the natives leak: "
                + "close() must set closeRequested. Body was: " + body,
                body.contains("closeRequested = true;"));
        assertTrue("the release must still happen on the ordinary path, where close() is called "
                + "from outside any dispatch (Escape, the host swapping screens). Body was: " + body,
                body.contains("closeQuietly();"));
    }

    @Test
    public void dispatchBracketsTheCallAndHonoursTheDeferredCloseOnTheWayOut() throws Exception {
        String body = SourceScan.bodyOf(SourceScan.read(SURFACE), "dispatch");

        assertTrue("dispatch() must increment the depth BEFORE calling into the view, or a close "
                + "raised by the handler would read a depth of zero and dispose underneath it. "
                + "Body was: " + body,
                body.indexOf("dispatchDepth++") >= 0
                        && body.indexOf("dispatchDepth++") < body.indexOf("call.run()"));
        assertTrue("dispatch() must decrement on the way out: the outermost unwinding is the "
                + "first moment the view is off the stack. Body was: " + body,
                body.contains("dispatchDepth--"));
    }

    @Test
    public void theDeferredReleaseIsInAFinallySoAFaultingHandlerStillFreesTheNatives() throws Exception {
        String body = SourceScan.bodyOf(SourceScan.read(SURFACE), "dispatch");

        assertTrue("the catch-and-report arm exists so input falls through to the game; putting "
                + "the release beside it instead of in a finally would skip the release for "
                + "exactly the handlers most likely to be faulting. Body was: " + body,
                body.contains("finally") && body.indexOf("finally") < body.indexOf("closeRequested"));
    }
}
