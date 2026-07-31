package net.marcloud.mcp.dwm.qml;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Flickable;
import io.github.timer_err.qml4j.render.items.core.Item;
import net.marcloud.mcp.dwm.ui.UiInput;
import net.marcloud.mcp.dwm.ui.UiSurface;
import net.marcloud.mcp.dwm.ui.UiWindowHost;

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

    /**
     * Who answers the scene's window verbs, or null when nothing does.
     *
     * <p>Null is a supported state, not an oversight: a scene opened by a test has no screen behind
     * it, and a caption button that asks to be minimised should then do nothing rather than fault.
     */
    private final UiWindowHost windowHost;

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
        this(qmlPath, null);
    }

    /**
     * @param qmlPath    resource path of the scene to load, resolved by qml4j's loader
     * @param windowHost answers the scene's window verbs, or null for none
     */
    public QmlUiSurface(String qmlPath, UiWindowHost windowHost) {
        this.qmlPath = qmlPath;
        this.windowHost = windowHost;
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
                // The scene's own directory, so an Image.source relative to the scene resolves.
                // qml4j hands image paths to the loader unresolved, unlike document imports.
                .resources(new ClasspathResources(ClasspathResources.baseDirOf(qmlPath)))
                // Live kernel/board state, before load(): qml4j's compiler has to know the name
                // to accept it as a free identifier in a binding, so registering it afterwards
                // would make every scene that reads it fail to compile.
                .context(DwmContext.NAME, new DwmContext())
                // Window verbs, as their own namespace rather than more methods on Dwm: asking to
                // be minimised is a request about the window, not knowledge about the kernel.
                .context(WindowCommands.NAME, new WindowCommands(windowHost));
            view.setClipboard(new GlfwClipboard());
            view.load(source, ClasspathResources.baseDirOf(qmlPath));

            // Root geometry is set on the first frame, by the extent-changed branch in frame():
            // lastWidthPx starts at -1, so that branch always runs once. Sizing it here too would
            // be a second place to keep right for no gain.

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
     * The composition loop: repaint the scene into the offscreen layer, then blit it.
     *
     * <p>This is the shape a compositor has, adapted to living inside someone else's frame. MC
     * redraws the whole world every frame, so the <em>composite</em> can never be skipped — skip it
     * and the menu vanishes.
     *
     * <p><b>The scene repaint is no longer skipped either, and the reason is a deadlock rather than
     * a performance judgement.</b> This used to consult qml4j's global property change counter and
     * repaint only when it had moved — level-zero damage tracking. But
     * {@code QmlView.renderFrame} ticks the animation tree <em>inside itself</em>, before comparing
     * versions: an animation only advances, and therefore only moves the counter, as a
     * <em>consequence</em> of being rendered. Deciding whether to render by looking at the counter
     * first is circular, so once anything began animating the counter stopped moving, the repaint
     * was skipped, the tick never happened, and the animation froze on its first frame.
     *
     * <p>What that cost, measured on a live client: a wheel notch jumped {@code contentY} by
     * qml4j's full 48px {@code WHEEL_STEP} in one frame with no interpolation — the "scrolling is
     * stuttery" report — and {@code Flickable}'s smooth-scroll target, the toggle knob's 83ms
     * travel and the expander chevron's rotation had never once played. Driving
     * {@code renderFrame} directly showed the smoothing working immediately: contentY ran
     * 0 → 89 → 125 → 127 over consecutive frames.
     *
     * <p>The price is small and was measured rather than assumed: 74µs per frame while scrolling
     * against 68µs idle, i.e. +6µs on a ~16ms frame budget. Damage tracking was saving about 8% of
     * a cost that is already 0.4% of the frame, in exchange for every animation in the UI.
     *
     * <p>Falls back to painting straight at MC's framebuffer if the offscreen layer cannot be
     * created, so a driver that will not give us a render target costs efficiency, not the UI.
     */
    private void composite() {
        if (backend.beginLayerScene()) {
            view.renderFrame(backend);
            backend.endLayerScene();
        } else {
            // No layer available: paint direct at MC's framebuffer.
            view.renderFrame(backend);
            return;
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
    //
    // Buttons are translated too, and for the same reason the coordinates are: the SPI carries
    // LWJGL2's zero-based index and qml4j wants Qt's bitmask, where left is 1. See
    // QmlButtonMap for why passing the index through looked like it worked.

    @Override
    public boolean pointerDown(float xPx, float yPx, int button) {
        int qmlButton = QmlButtonMap.toQml(button);
        return dispatch(() -> view.dispatchPointerDown(lx(xPx), ly(yPx), qmlButton));
    }

    @Override
    public boolean pointerUp(float xPx, float yPx, int button) {
        int qmlButton = QmlButtonMap.toQml(button);
        return dispatch(() -> view.dispatchPointerUp(lx(xPx), ly(yPx), qmlButton));
    }

    @Override
    public boolean pointerMove(float xPx, float yPx) {
        return dispatch(() -> view.dispatchPointerMove(lx(xPx), ly(yPx)));
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Routed to the Flickable's smooth-scroll target rather than to qml4j's own wheel
     * handling.</b> {@code EventDispatcher.dispatchWheel} writes {@code contentY} directly and sets
     * {@code targetY} to the same value, so a notch teleports the content by its full 48px
     * {@code WHEEL_STEP} with nothing left for the animator to interpolate. Measured: one notch
     * moved {@code contentY} 0 → 48 in a single frame and stayed there — about three quarters of a
     * settings card jumping past at once, which is what "scrolling is stuttery" was describing.
     *
     * <p>{@code Flickable.nudge} instead moves only the TARGET and sets the smoothing flag, leaving
     * {@code tick} to ease the content toward it over several frames. That tick happens inside
     * {@code renderFrame}, which is why this only works now that {@link #composite} repaints every
     * frame.
     *
     * <p>Falls back to {@code dispatchWheel} when the point is not over a Flickable, so a scene
     * without one behaves exactly as before.
     */
    @Override
    public boolean wheel(float xPx, float yPx, float dxNotches, float dyNotches) {
        // Position is spatial and scales; the notch deltas are not distances and do not.
        return dispatch(() -> {
            Flickable scroller = flickableAt(lx(xPx), ly(yPx));
            if (scroller == null) {
                return view.dispatchWheel(lx(xPx), ly(yPx), dxNotches, dyNotches);
            }
            // Same step qml4j uses, so the distance per notch is unchanged — only its delivery is.
            // Negated because a wheel reports +y for up while content scrolls the other way.
            scroller.nudge(-dxNotches * WHEEL_STEP, -dyNotches * WHEEL_STEP);
            return true;
        });
    }

    /**
     * qml4j's own wheel step, restated because {@code EventDispatcher.WHEEL_STEP} is private.
     *
     * <p>Kept identical on purpose: this class changes how a notch is delivered, not how far it
     * goes. A different value here would silently make dwm scroll at a different rate from every
     * other qml4j host.
     */
    private static final float WHEEL_STEP = 48.0F;

    /**
     * The innermost {@link Flickable} containing a point, in logical units, or null.
     *
     * <p>Walks the tree the way qml4j's own hit test does — deepest match wins, so a nested
     * scroller inside a scrolling page would take its own wheel events. Bounds are checked in each
     * item's parent space, with a Flickable's own scroll offset removed on the way down, because
     * that is the transform the renderer applies.
     */
    private Flickable flickableAt(float x, float y) {
        Item root = view.root();
        return root == null ? null : findFlickable(root, x, y);
    }

    private static Flickable findFlickable(Item node, float x, float y) {
        if (node == null || !node.isVisible()) {
            return null;
        }
        float localX = x - node.x.peekFloat();
        float localY = y - node.y.peekFloat();
        if (localX < 0 || localY < 0
            || localX > node.width.peekFloat() || localY > node.height.peekFloat()) {
            return null;
        }
        float childX = localX;
        float childY = localY;
        if (node instanceof Flickable) {
            Flickable flickable = (Flickable) node;
            childX += flickable.contentX.peekFloat();
            childY += flickable.contentY.peekFloat();
        }
        // Children first, so the innermost scroller wins.
        for (int i = node.children.size() - 1; i >= 0; i--) {
            Flickable hit = findFlickable(node.children.get(i), childX, childY);
            if (hit != null) {
                return hit;
            }
        }
        return node instanceof Flickable ? (Flickable) node : null;
    }

    /** Framebuffer pixels to logical units, horizontally. */
    private float lx(float xPx) {
        return uiScale > 0.0F ? xPx / uiScale : xPx;
    }

    /** Framebuffer pixels to logical units, vertically. */
    private float ly(float yPx) {
        return uiScale > 0.0F ? yPx / uiScale : yPx;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>The third argument is qml4j's {@code down} flag, not a modifier.</b> Its signature
     * is {@code dispatchKey(keyCode, text, down, shift)}, and passing {@code shift} there —
     * which this did until measured — shifts every argument one place left. With Shift up,
     * {@code down} became false, so qml4j emitted {@code Keys.released} instead of
     * {@code pressed}, skipped every specific signal ({@code escapePressed},
     * {@code returnPressed}, …) and the Tab focus move, then returned true from its
     * {@code !down} branch: the key was consumed and nothing happened. Typing worked ONLY
     * while Shift was held. A literal {@code true} is correct because this path is reached
     * exclusively for presses — vanilla's {@code GuiScreen.handleKeyboardInput()} calls
     * {@code keyTyped} only when {@code Keyboard.getEventKeyState()} is true.
     *
     * <p>{@code control} has no place in {@code dispatchKey}: qml4j does not model Ctrl there,
     * exposing the clipboard as its own API instead. Ctrl combos are routed to it below.
     */
    @Override
    public boolean key(int keyCode, String text, boolean shift, boolean control) {
        // Ctrl combos are clipboard verbs in qml4j's model, reached through their own entry
        // points rather than as a modifier on a key event.
        if (control && text != null && text.length() == 1) {
            Boolean handled = clipboardVerb(Character.toLowerCase(text.charAt(0)));
            if (handled != null) {
                return handled;
            }
        }
        int qmlKey = QmlKeyMap.toQml(keyCode);
        if (qmlKey == 0 && (text == null || text.isEmpty())) {
            // Neither an editing key qml4j models nor printable text: nothing to send.
            return false;
        }
        return dispatch(() -> view.dispatchKey(qmlKey, text, true, shift));
    }

    /**
     * Run the clipboard verb bound to {@code letter}, or return null when it is not one.
     *
     * <p>Null rather than false distinguishes "not a clipboard combo, keep processing" from
     * "was one, and it did not consume" — collapsing the two would swallow every other Ctrl
     * combo the scene might want.
     */
    private Boolean clipboardVerb(char letter) {
        switch (letter) {
            case 'c': return dispatch(() -> view.copy());
            case 'x': return dispatch(() -> view.cut());
            case 'v': return dispatch(() -> view.paste());
            default:  return null;
        }
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
}
