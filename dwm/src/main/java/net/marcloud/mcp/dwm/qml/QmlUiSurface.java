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
 * plain-type SPI on the other. Keeping that knowledge here is what keeps qml4j types out of
 * everything above the adapter package.
 *
 * <p><b>Everything is fault-isolated.</b> This runs inside MC's frame on the render thread; an
 * exception escaping into the game loop would crash the client, so a failure is recorded, the
 * surface goes inert, and the game continues without UI. That is also the module contract:
 * dwm is a detachable auxiliary and must never be able to take the client down.
 *
 * <p><b>A frame with no live GL wrap is reported, but is NOT such a failure.</b> The backend retries
 * a failed wrap on a cooldown, so that state is recoverable, and going inert would be the one thing
 * that made it permanent — {@link #frame} returns immediately once inert. Instead the reason is
 * recorded in {@link #lastError()} and {@link #isOpen()} answers false until a wrap takes: an
 * overlay nobody can see must not report itself healthy.
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
    /**
     * Nesting depth of {@link #dispatch}, and the reason {@code close()} is sometimes deferred.
     *
     * <p>Clicking the caption's X runs, inside a single {@code view.dispatchPointerUp}:
     * {@code onClicked} -> {@code WindowCommands.close()} -> {@code mc.displayGuiScreen(null)}
     * -> {@code QmlGuiScreen.onGuiClosed()} -> {@code close()} -> {@code view.dispose()}. Control
     * then unwinds back into the {@code QmlView} that was just disposed, with qml4j still owing
     * it a return. {@code dispatch}'s guard cannot catch this — it tests {@code open} BEFORE
     * {@code call.run()}, and {@code close()} only clears {@code open} after the dispose has
     * already happened. Disposing the object whose stack frame you are inside is a use-after-
     * dispose, and this module has already crashed the client twice on native faults.
     */
    private int dispatchDepth;

    /** A close asked for from inside a dispatch; honoured when that dispatch unwinds. */
    private boolean closeRequested;
    /** Set once a no-wrap frame has been logged, so the report is not a 60Hz log line. */
    private boolean noWrapReported;
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
        // Skija issues raw GL from here as much as from a frame: DirectContext.makeGL() talks to
        // the driver, and view.load() uploads glyph and image textures. closeQuietly() below then
        // runs those same paths in reverse. The frame path has always been bracketed for that
        // reason -- see GlStateGuard for the leaks (buffer bindings, attribute arrays, the FBO and
        // the program) that crashed a live client when they went unrestored -- and these two are
        // the only other places dwm lets Skija touch GL, so they get the same bracket. initGui()
        // makes this path run again on every resize.
        GlStateGuard.enter();
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
                // Window verbs, as their own namespace rather than more methods on Dwm: asking the
                // window to close is a request about the window, not knowledge about the kernel.
                .context(WindowCommands.NAME, new WindowCommands(windowHost));
            view.setClipboard(new GlfwClipboard());
            view.load(source, ClasspathResources.baseDirOf(qmlPath));

            // Root geometry is set on the first frame, by the extent-changed branch in frame():
            // lastWidthPx starts at -1, so that branch always runs once. Sizing it here too would
            // be a second place to keep right for no gain. The extents are RESET for the same
            // reason -- they describe the view that was here a moment ago, and this is a new one;
            // left set, a re-open at the same size would skip sizeRoot() and leave the fresh root
            // on the scene's declared fallback instead of the framebuffer's real extent.
            lastWidthPx = -1;
            lastHeightPx = -1;

            open = true;
            inert = false;
            return true;
        } catch (Throwable t) {
            // A missing or malformed .qml, or a Skija native that would not load. Report once
            // and stay down rather than throwing into the game loop.
            lastError = String.valueOf(t);
            System.err.println("[dwm] failed to open qml scene " + qmlPath + ": " + t);
            // releaseNatives(), not closeQuietly(): this path is already inside the guard, and
            // GlStateGuard is not reentrant -- its capture lives in static fields, so a nested
            // pair would make the outer leave() restore the inner capture, i.e. the state Skija
            // left behind instead of MC's.
            releaseNatives();
            inert = true;
            return false;
        } finally {
            // Unconditional, as in frame(): a fault must not leave MC's shadowed GL state
            // disagreeing with the driver.
            GlStateGuard.leave();
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
                noWrapReported = false;
                composite();
            } else {
                reportNoWrap(widthPx, heightPx);
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
     * Record that this frame composited nothing, and why.
     *
     * <p>Silence was the defect here. {@link #frame} gates the composite on
     * {@link McpFboSurfaceBackend#hasSurface()} and used to just fall through when the answer was
     * no: the scene stayed unpainted while {@link #isOpen()} kept saying healthy and
     * {@link #lastError()} kept saying null, so a UI that had never appeared looked exactly like a
     * UI that was working. The wrap is now retried by the backend on a cooldown, so this is
     * deliberately NOT {@code inert = true}: that flag stops {@link #frame} running at all, which
     * would turn a recoverable state into a permanent one, and it is why the honest answer here is
     * "this frame drew nothing, here is why" rather than "the surface is dead".
     */
    private void reportNoWrap(int widthPx, int heightPx) {
        String why = backend.wrapFailure();
        lastError = why != null ? why
            : "no Skia wrap for framebuffer " + liveFboId + " at " + widthPx + "x" + heightPx;
        if (!noWrapReported) {
            // Once per failure episode rather than once per frame: an uncomposited overlay is a
            // 60Hz condition, and a 60Hz log line is its own defect.
            noWrapReported = true;
            System.err.println("[dwm] frame composited nothing: " + lastError);
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
        // Cleared first, unconditionally, so a second call while a deferred release is pending
        // is a no-op rather than a second dispose.
        open = false;
        if (dispatchDepth > 0) {
            // The X on the caption lands here, from inside view.dispatchPointerUp. Disposing
            // now would free the object qml4j is about to resume executing in; the release runs
            // at the end of the outermost dispatch instead, where the view is off the stack.
            closeRequested = true;
            return;
        }
        closeQuietly();
    }

    private void closeQuietly() {
        // The other half of the pair documented in open(): view.dispose() and the backend's
        // context.close() are Skija releasing GL objects, so they run under the same bracket every
        // other Skija entry point does.
        GlStateGuard.enter();
        try {
            releaseNatives();
        } finally {
            GlStateGuard.leave();
        }
    }

    /**
     * Dispose the view and the backend, swallowing teardown faults.
     *
     * <p>Split out of {@link #closeQuietly()} because {@link #open()}'s failure path needs the
     * release while it is ALREADY inside the guard: {@link GlStateGuard} is not reentrant — its
     * capture lives in static fields — so a nested pair would leave the outer {@code leave()}
     * restoring the inner capture, i.e. the state Skija left behind instead of MC's.
     */
    private void releaseNatives() {
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
        // "Open" has to mean "can put pixels on the screen". A surface with no live Skia wrap
        // renders nothing, so it answers false here -- that was the state which reported healthy
        // while every frame was skipped. Deliberately not the same as inert: the backend retries,
        // so this goes true again the moment a wrap takes.
        return open && !inert && backend != null && backend.hasSurface();
    }

    /** Last failure, or null if none. Diagnostic only. */
    public String lastError() {
        return lastError;
    }

    // ---- test seam --------------------------------------------------------------
    //
    // Package-private, and deliberately not public: the live ITs and scripts/live-dwm-probe.py
    // need to see this state, but the surface's PUBLIC contract is UiSurface + UiInput, and
    // widening it so a test can look inside would make the seam permanent.
    //
    // They exist because the alternative was reflection on FIELD NAMES, which pinned the layout
    // of this class from the outside: 37 of the 43 live ITs read `view`, `backend` or `uiScale`
    // through getDeclaredField, so renaming or moving any of the three broke all of them at once
    // and the compiler said nothing. The ITs now call these directly and javac checks them; the
    // probe still reflects, because it runs inside core and cannot link dwm at all, but it asks
    // for a METHOD, so the only thing it pins is this seam rather than the field layout behind it.

    /**
     * The scene root, for the element-table walk.
     *
     * <p>Package-private and named for what it is rather than exposing the {@link QmlView}
     * itself: the element bridge needs the tree, not the renderer, and handing out the view
     * would let a caller reach the whole qml4j surface from outside this package.
     */
    Item rootItem() {
        return view == null ? null : view.root();
    }

    QmlView view() {
        return view;
    }

    McpFboSurfaceBackend backend() {
        return backend;
    }

    /** The raw scale in force, unclamped: a caller wanting a safe divisor applies its own floor. */
    float uiScale() {
        return uiScale;
    }

    /** True once something faulted, which is why {@link #isOpen()} can be false while open. */
    boolean isInert() {
        return inert;
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
        dispatchDepth++;
        try {
            return call.run();
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            System.err.println("[dwm] input dispatch faulted: " + t);
            return false;
        } finally {
            // The outermost unwinding is the first moment the view is off the stack, so it is
            // where a close asked for by a click handler is honoured. In the finally rather
            // than after the try, so a faulting handler still releases rather than leaking.
            dispatchDepth--;
            if (dispatchDepth == 0 && closeRequested) {
                closeRequested = false;
                closeQuietly();
            }
        }
    }

    /** Java 8 has no BooleanSupplier that can throw; this keeps the call sites terse. */
    private interface BooleanCall {
        boolean run() throws Throwable;
    }
}
