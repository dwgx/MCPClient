package net.marcloud.mcp.dwm.qml;

import io.github.timer_err.qml4j.render.Clipboard;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.Display;

/**
 * qml4j {@link Clipboard} backed by GLFW.
 *
 * <p>Deliberately not AWT. Vanilla's {@code GuiScreen} clipboard helpers go through
 * {@code java.awt.Toolkit}, which starts AppKit — and on macOS, where GLFW already owns the
 * main thread under {@code -XstartOnFirstThread}, a process with both up can no longer exit:
 * the copy succeeds and then quitting the game hangs. That is why the game currently runs with
 * {@code -Djava.awt.headless=true} and vanilla text fields have no clipboard on macOS
 * (docs/macos/known-issues.md, MK-1).
 *
 * <p>Text inside a qml4j scene does not inherit that limitation, because qml4j takes its
 * clipboard as an interface and this implementation goes straight to GLFW. So UI built here
 * gets working copy/paste on every platform, without editing a line of the vanilla client.
 *
 * <p>Both directions are best-effort and never throw: qml4j calls these from key handling on
 * the render thread, and a clipboard failure must not interrupt a frame.
 */
final class GlfwClipboard implements Clipboard {

    @Override
    public String getText() {
        if (!Display.isCreated()) {
            return null;
        }
        try {
            return GLFW.glfwGetClipboardString(Display.getWindowHandle());
        } catch (RuntimeException e) {
            // Empty, non-text, or unavailable clipboard: qml4j treats null as "nothing".
            return null;
        }
    }

    @Override
    public void setText(String text) {
        if (text == null || !Display.isCreated()) {
            return;
        }
        try {
            GLFW.glfwSetClipboardString(Display.getWindowHandle(), text);
        } catch (RuntimeException e) {
            // Best-effort, exactly as the AWT path was.
        }
    }
}
