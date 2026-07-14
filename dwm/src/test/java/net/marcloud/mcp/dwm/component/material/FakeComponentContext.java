package net.marcloud.mcp.dwm.component.material;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;
import net.marcloud.mcp.dwm.component.ComponentContext;
import net.marcloud.mcp.dwm.compositor.UiStateStore;
import net.marcloud.mcp.dwm.compositor.WidgetId;
import net.marcloud.mcp.dwm.theme.MdcTheme;

/** Mutable test double for {@link ComponentContext}. */
final class FakeComponentContext implements ComponentContext {

    private final RecordingDrawContext draw = new RecordingDrawContext();
    private final MdcTheme theme;
    private final FakeUiStateStore store;
    private final WidgetId id;
    private FrameInput input;
    private FrameMetrics metrics;

    FakeComponentContext(String rootKey) {
        this.theme = new FakeMdcTheme();
        this.store = new FakeUiStateStore();
        this.id = WidgetId.root(rootKey);
        this.input = FrameInput.none();
        this.metrics = new FrameMetrics(800, 600, 1f, 1f / 60f, 1L);
    }

    RecordingDrawContext recording() {
        return draw;
    }

    FakeUiStateStore fakeStore() {
        return store;
    }

    void setInput(FrameInput input) {
        this.input = input;
    }

    void setMetrics(FrameMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public DrawContext draw() {
        return draw;
    }

    @Override
    public MdcTheme theme() {
        return theme;
    }

    @Override
    public UiStateStore store() {
        return store;
    }

    @Override
    public FrameInput input() {
        return input;
    }

    @Override
    public FrameMetrics metrics() {
        return metrics;
    }

    @Override
    public WidgetId id() {
        return idTop();
    }

    @Override
    public WidgetId childId(String key) {
        return WidgetId.of(idTop(), key);
    }

    private final java.util.ArrayDeque<WidgetId> idStack = new java.util.ArrayDeque<>();

    private WidgetId idTop() {
        return idStack.isEmpty() ? id : idStack.peek();
    }

    @Override
    public void pushId(String key) {
        idStack.push(WidgetId.of(idTop(), key));
    }

    @Override
    public void popId() {
        if (!idStack.isEmpty()) {
            idStack.pop();
        }
    }

    @Override
    public net.marcloud.mcp.dwm.backend.TextMetrics measureText(
            net.marcloud.mcp.dwm.backend.FontHandle font, CharSequence text, float sizePx) {
        // Deterministic fixed-advance metrics for tests (matches the old approxTextWidth
        // 0.6 advance so existing width assertions still hold).
        int n = text == null ? 0 : text.length();
        return new net.marcloud.mcp.dwm.backend.TextMetrics(
                n * sizePx * 0.6f, sizePx * 0.8f, sizePx * 0.2f);
    }
}
