package net.marcloud.mcp.dwm.qml;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.QmlView;
import net.marcloud.mcp.dwm.ui.UiInput;
import net.marcloud.mcp.dwm.ui.UiKeys;
import net.marcloud.mcp.dwm.ui.UiSurface;

/**
 * Drives a qml4j scene as a DWM {@link UiSurface} / {@link UiInput}.
 *
 * <p>This is the only class that knows both vocabularies: qml4j and Skija on one side, dwm's
 * plain-type SPI on the other. Keeping that knowledge here is what lets the backend be
 * swapped without touching anything above.
 *
 * <p><b>Everything is fault-isolated.</b> This runs inside MC's frame on the render thread; an
 * exception escaping into the game loop would crash the client, so a failure is recorded, the
 * surface goes inert, and the game continues without UI. That is also the module contract:
 * dwm is a detachable auxiliary and must never be able to take the client down.
 */
public final class QmlUiSurface implements UiSurface, UiInput {

    private final String qmlPath;

    private McpFboSurfaceBackend backend;
    private QmlView view;
    private boolean open;
    /** Set when something faulted; keeps us from retrying a broken scene every frame. */
    private boolean inert;
    private String lastError;

    /**
     * @param qmlPath resource path of the scene to load, resolved by qml4j's loader
     */
    public QmlUiSurface(String qmlPath) {
        this.qmlPath = qmlPath;
    }

    /**
     * The framebuffer id qml4j should render into, refreshed each frame by the caller.
     * Kept separate from {@link #frame} because only the caller can ask MC for it.
     */
    private int liveFboId = -1;

    /** Tell the surface which framebuffer MC currently has bound. Call before {@link #frame}. */
    public void setFramebufferId(int fboId) {
        this.liveFboId = fboId;
    }

    @Override
    public boolean open(int widthPx, int heightPx) {
        if (open) {
            return true;
        }
        try {
            backend = new McpFboSurfaceBackend();
            backend.init(widthPx, heightPx);

            // load() takes QML SOURCE, not a path — passing a path makes the parser try to
            // parse the path itself. The host reads the bytes and supplies a ResourceLoader so
            // the document's own relative imports resolve.
            String source = ClasspathResources.readText(qmlPath);
            if (source == null) {
                throw new IllegalStateException("scene not found on the classpath: " + qmlPath);
            }

            view = QmlView.withStockTypes(new QmlEngine())
                .resources(new ClasspathResources());
            view.setClipboard(new GlfwClipboard());
            view.load(source, ClasspathResources.baseDirOf(qmlPath));

            open = true;
            inert = false;
            return true;
        } catch (Throwable t) {
            // A missing or malformed .qml, or a Skija native that would not load. Report once
            // and stay down rather than throwing into the game loop.
            lastError = String.valueOf(t);
            System.err.println("[dwm] failed to open qml scene " + qmlPath + ": " + t);
            closeQuietly();
            inert = true;
            return false;
        }
    }

    @Override
    public void frame(int widthPx, int heightPx, long nanoTime) {
        if (!open || inert || view == null || backend == null) {
            return;
        }
        GlStateGuard.enter();
        try {
            // Retarget first: a resize recreated MC's framebuffer GL objects, possibly under a
            // new id, and rendering into the stale wrap is what turns the world black.
            backend.frameTarget(widthPx, heightPx, liveFboId);
            if (backend.hasSurface()) {
                view.tickAnimations(nanoTime);
                view.renderFrame(backend);
            }
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            System.err.println("[dwm] frame faulted, going inert: " + t);
            inert = true;
        } finally {
            // Unconditional: MC's shadowed GL state must be restored even on a fault, or the
            // game stops rendering from the next frame on.
            GlStateGuard.leave();
        }
    }

    @Override
    public void close() {
        closeQuietly();
        open = false;
    }

    private void closeQuietly() {
        try {
            if (view != null) {
                view.dispose();
            }
        } catch (Throwable ignored) {
            // Teardown faults are not actionable.
        }
        view = null;
        try {
            if (backend != null) {
                backend.dispose();
            }
        } catch (Throwable ignored) {
            // As above.
        }
        backend = null;
    }

    @Override
    public boolean isOpen() {
        return open && !inert;
    }

    /** Last failure, or null if none. Diagnostic only. */
    public String lastError() {
        return lastError;
    }

    // ---- UiInput. Coordinates arrive as framebuffer pixels, top-left origin ----

    @Override
    public boolean pointerDown(float xPx, float yPx, int button) {
        return dispatch(() -> view.dispatchPointerDown(xPx, yPx, button));
    }

    @Override
    public boolean pointerUp(float xPx, float yPx, int button) {
        return dispatch(() -> view.dispatchPointerUp(xPx, yPx, button));
    }

    @Override
    public boolean pointerMove(float xPx, float yPx) {
        return dispatch(() -> view.dispatchPointerMove(xPx, yPx));
    }

    @Override
    public boolean wheel(float xPx, float yPx, float dxNotches, float dyNotches) {
        return dispatch(() -> view.dispatchWheel(xPx, yPx, dxNotches, dyNotches));
    }

    @Override
    public boolean key(int keyCode, String text, boolean shift, boolean control) {
        int qmlKey = QmlKeyMap.toQml(keyCode);
        if (qmlKey == 0 && (text == null || text.isEmpty())) {
            // Neither an editing key qml4j models nor printable text: nothing to send.
            return false;
        }
        return dispatch(() -> view.dispatchKey(qmlKey, text, shift, control));
    }

    /** Runs a dispatch, treating any fault as "not consumed" so input falls through to the game. */
    private boolean dispatch(BooleanCall call) {
        if (!open || inert || view == null) {
            return false;
        }
        try {
            return call.run();
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            System.err.println("[dwm] input dispatch faulted: " + t);
            return false;
        }
    }

    /** Java 8 has no BooleanSupplier that can throw; this keeps the call sites terse. */
    private interface BooleanCall {
        boolean run() throws Throwable;
    }

    /** True when {@code keyCode} is the key that should dismiss the UI. */
    public static boolean isDismiss(int keyCode) {
        return keyCode == UiKeys.ESCAPE;
    }
}
