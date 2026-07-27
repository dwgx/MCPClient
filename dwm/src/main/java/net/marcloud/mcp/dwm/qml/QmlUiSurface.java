package net.marcloud.mcp.dwm.qml;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import net.marcloud.mcp.dwm.ui.UiInput;
import net.marcloud.mcp.dwm.ui.UiKeys;
import net.marcloud.mcp.dwm.ui.UiSurface;

import org.lwjgl.opengl.Display;

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

    /**
     * The DPI scale in force, refreshed each frame. Both the canvas transform and the inbound
     * pointer conversion read it, and they must agree or clicks land somewhere else entirely.
     */
    private float uiScale = 1.0F;

    /**
     * qml4j's property change counter as of the last scene repaint. Equal to the current value
     * means nothing in the scene moved and the cached image can be composited again.
     */
    private long renderedVersion = -1L;

    /** Last framebuffer extent the root was sized for, so a resize re-sizes it. */
    private int lastWidthPx = -1;
    private int lastHeightPx = -1;

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

            // Root geometry is set on the first frame, by the extent-changed branch in frame():
            // lastWidthPx starts at -1, so that branch always runs once. Sizing it here too would
            // be a second place to keep right for no gain.

            open = true;
            inert = false;
            renderedVersion = -1L;
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
            // Refresh the DPI scale each frame: the user can drag the window to a monitor with a
            // different scale, and it costs one field read.
            uiScale = Display.getContentScaleX();
            backend.setUiScale(uiScale);
            // Retarget first: a resize recreated MC's framebuffer GL objects, possibly under a
            // new id, and rendering into the stale wrap is what turns the world black. The
            // surface is sized in DEVICE pixels; only the canvas transform is logical.
            if (widthPx != lastWidthPx || heightPx != lastHeightPx) {
                lastWidthPx = widthPx;
                lastHeightPx = heightPx;
                sizeRoot(widthPx, heightPx);
            }
            backend.frameTarget(widthPx, heightPx, liveFboId);
            if (backend.hasSurface()) {
                composite();
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

    /**
     * Give the scene root its geometry, in LOGICAL units.
     *
     * <p>Logical, not device: the canvas carries the DPI transform, so a root sized in device
     * pixels would be twice the visible area on a Retina display and hit testing would accept
     * points outside the window.
     */
    private void sizeRoot(int widthPx, int heightPx) {
        Item root = view.root();
        if (root == null) {
            return;
        }
        float scale = uiScale > 0.0F ? uiScale : 1.0F;
        root.x.set(0.0F);
        root.y.set(0.0F);
        root.width.set(widthPx / scale);
        root.height.set(heightPx / scale);
    }

    /**
     * The composition loop: repaint the scene only when it changed, blit it every frame.
     *
     * <p>This is the shape a compositor has, adapted to living inside someone else's frame. MC
     * redraws the whole world every frame, so the <em>composite</em> can never be skipped — skip
     * it and the menu vanishes. The <em>scene repaint</em> is the expensive half and is what gets
     * skipped, which is level-zero damage tracking: notice nothing changed, stop rendering.
     *
     * <p>Dirtiness comes from qml4j's own global property change counter, the same signal its
     * renderer uses internally for its idle-layout fast path. Any binding, animation, timer or
     * input that touched a property moves it.
     *
     * <p>Falls back to painting straight at MC's framebuffer if the offscreen layer cannot be
     * created, so a driver that will not give us a render target costs efficiency, not the UI.
     */
    private void composite() {
        long version = Property.changeVersion();
        boolean sceneChanged = version != renderedVersion || !backend.hasLayerSnapshot();

        if (sceneChanged) {
            if (backend.beginLayerScene()) {
                view.renderFrame(backend);
                backend.endLayerScene();
                renderedVersion = version;
            } else {
                // No layer available: paint direct and take the cost every frame.
                view.renderFrame(backend);
                return;
            }
        }
        backend.compositeLayer();
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

    // ---- UiInput ----------------------------------------------------------------
    //
    // Coordinates arrive as framebuffer pixels (the SPI contract) and are divided by the DPI
    // scale on the way in, because the scene is laid out in logical units. Skipping this makes
    // every hit test miss by the scale factor: on a Retina display a click would land at twice
    // the intended point, so only the top-left quarter of the UI would be reachable.

    @Override
    public boolean pointerDown(float xPx, float yPx, int button) {
        return dispatch(() -> view.dispatchPointerDown(lx(xPx), ly(yPx), button));
    }

    @Override
    public boolean pointerUp(float xPx, float yPx, int button) {
        return dispatch(() -> view.dispatchPointerUp(lx(xPx), ly(yPx), button));
    }

    @Override
    public boolean pointerMove(float xPx, float yPx) {
        return dispatch(() -> view.dispatchPointerMove(lx(xPx), ly(yPx)));
    }

    @Override
    public boolean wheel(float xPx, float yPx, float dxNotches, float dyNotches) {
        // Position is spatial and scales; the notch deltas are not distances and do not.
        return dispatch(() -> view.dispatchWheel(lx(xPx), ly(yPx), dxNotches, dyNotches));
    }

    /** Framebuffer pixels to logical units, horizontally. */
    private float lx(float xPx) {
        return uiScale > 0.0F ? xPx / uiScale : xPx;
    }

    /** Framebuffer pixels to logical units, vertically. */
    private float ly(float yPx) {
        return uiScale > 0.0F ? yPx / uiScale : yPx;
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
