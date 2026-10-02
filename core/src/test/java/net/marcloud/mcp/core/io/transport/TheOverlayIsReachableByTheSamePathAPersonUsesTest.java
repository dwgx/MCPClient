package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertTrue;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.junit.Test;

/**
 * The overlay panel must be reachable by an agent, and by the SAME path a person uses.
 *
 * <p>This is the second half of audit 2.6, and it was the half that was missing. The panel
 * publishes one invisible {@code GuiButton} per QML {@code MouseArea} into {@code buttonList}, so
 * {@code gui_snapshot} sees it and {@code gui_click_element} drives it — but the panel itself
 * could only be opened with RSHIFT, which is a GLFW event, and RSHIFT is **not in vanilla's
 * keybind array at all**. {@code press_key_binding} therefore answered
 * {@code bindingClaimed=true} and nothing happened, live. The one surface in the project a person
 * could open and an agent could not is precisely the gap 2.6 exists to close, reopened from the
 * other side.
 *
 * <p>Two properties are pinned, and the second is the one that matters:
 * <ul>
 *   <li>the tool exists and is registered, so it is callable at all;</li>
 *   <li>it calls the <b>same method the key hook calls</b>. A parallel path would be a second
 *       implementation of "open the panel" and would drift — the guard that refuses to replace
 *       another screen, the toggle-not-stack behaviour, the construction through
 *       {@code DwmEntry} — and then a person and an agent would be operating different UIs, which
 *       is the defect this exists to remove.</li>
 * </ul>
 */
public final class TheOverlayIsReachableByTheSamePathAPersonUsesTest {

    private static ToolRegistry registry() {
        return new ToolRegistry(new ToolContext(null, null, null, null, null));
    }

    private static SyncToolSpecification tool() {
        for (SyncToolSpecification s : registry().all()) {
            if (s.tool().name().equals("open_overlay")) {
                return s;
            }
        }
        throw new AssertionError("open_overlay is not in all(): the overlay stays person-only, "
                + "which is the gap this closes");
    }

    @Test
    public void itIsRegistered() {
        assertTrue(tool().tool().name().equals("open_overlay"));
    }

    @Test
    public void itTakesNoArguments() {
        // Nothing to parameterise: it toggles. Arguments would be a way for two callers to
        // disagree about what "open" means.
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> schema =
                (java.util.Map<String, Object>) tool().tool().inputSchema();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> props =
                (java.util.Map<String, Object>) schema.get("properties");
        assertTrue("it should offer no properties: " + props, props == null || props.isEmpty());
    }

    @Test
    public void itReachesTheSameMethodTheHotkeyCalls() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/net/marcloud/mcp/core/compat/patches/DwmHotkey.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        String toolSrc = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/net/marcloud/mcp/core/io/transport/ToolRegistry.java")),
                java.nio.charset.StandardCharsets.UTF_8);

        assertTrue("the hotkey hook must call the shared entry point rather than its own copy, or "
                + "the two paths can drift: " + src, src.contains("toggleScreenForTool()"));
        assertTrue("and the tool must call that same name: " + toolSrc,
                toolSrc.contains("toggleScreenForTool"));
    }

    @Test
    public void itSaysWhyItDidNothingWhenTheOverlayIsNotArmed() {
        // run-mcp.bat does not arm the overlay; run-mcp-overlay.bat does. "No panel" is a launch
        // condition the caller needs to see, not a success and not a silence.
        String d = tool().tool().description();
        assertTrue("the description must name the launcher that arms it, or a caller under "
                + "run-mcp.bat cannot tell a missing panel from a broken one: " + d,
                d.contains("run-mcp-overlay.bat"));
    }
}
