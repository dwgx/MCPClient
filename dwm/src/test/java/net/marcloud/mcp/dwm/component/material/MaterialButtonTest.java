package net.marcloud.mcp.dwm.component.material;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.compositor.WidgetId;
import net.marcloud.mcp.dwm.component.material.RecordingDrawContext.Call;
import net.marcloud.mcp.dwm.component.material.RecordingDrawContext.Op;

/**
 * Non-vacuous MaterialButton tests: colors must match FakeMdcTheme tokens,
 * state must live in the store (not the button), interaction must change draws.
 */
public class MaterialButtonTest {

    private FakeComponentContext ctx;
    private MaterialButton button;

    @Before
    public void setUp() {
        ctx = new FakeComponentContext("btn-test");
        button = new MaterialButton("Save", MaterialButton.Variant.FILLED);
    }

    @Test
    public void filledDrawsContainerWithThemePrimaryAndFullCorner() {
        button.render(ctx, 10f, 20f, 120f, 40f);

        List<Call> rounded = ctx.recording().of(Op.ROUNDED_RECT);
        assertFalse("must draw at least the container rounded rect", rounded.isEmpty());

        Call container = rounded.get(0);
        assertEquals("container color must be theme PRIMARY", FakeMdcTheme.PRIMARY, container.argb());
        // f = [x,y,w,h,radius]
        assertEquals(10f, container.f()[0], 0.01f);
        assertEquals(20f, container.f()[1], 0.01f);
        assertEquals(120f, container.f()[2], 0.01f);
        assertEquals(40f, container.f()[3], 0.01f);
        float expectedR = Math.min(FakeMdcTheme.CORNER_FULL, 20f);
        assertEquals("corner must come from theme FULL (clamped to half-height)",
                expectedR, container.f()[4], 0.01f);
    }

    @Test
    public void filledDrawsLabelWithOnPrimaryAndLabelLargeSize() {
        button.render(ctx, 0f, 0f, 100f, 40f);

        List<Call> texts = ctx.recording().of(Op.TEXT);
        assertEquals(1, texts.size());
        Call t = texts.get(0);
        assertEquals(FakeMdcTheme.ON_PRIMARY, t.argb());
        assertEquals(FakeMdcTheme.LABEL_LARGE_PX, t.f()[0], 0.01f);
        assertEquals("Save", t.extra());
    }

    @Test
    public void measureUsesLabelLargeAndMinHeight() {
        Component.Size size = button.measure(ctx);
        float expectedW = MaterialButton.PAD_H_DP * 2f
                + MaterialButton.approxTextWidth("Save", FakeMdcTheme.LABEL_LARGE_PX);
        assertEquals(expectedW, size.width(), 0.01f);
        assertEquals(MaterialButton.HEIGHT_DP, size.height(), 0.01f);
    }

    @Test
    public void hoverAfterTickDrawsStateLayerWithOnPrimaryAlpha() {
        // Frame 1: enter hover (sets target only).
        ctx.setInput(pointer(50f, 20f, 0));
        button.render(ctx, 0f, 0f, 100f, 40f);
        ctx.fakeStore().tickAll(StateLayerState.TRANSITION_SECONDS);

        // Frame 2: draw with eased alpha.
        ctx.recording().clear();
        button.render(ctx, 0f, 0f, 100f, 40f);

        int expected = Argb.withAlpha(FakeMdcTheme.ON_PRIMARY, StateLayerState.HOVER_ALPHA);
        boolean found = false;
        for (Call c : ctx.recording().of(Op.ROUNDED_RECT)) {
            if (c.argb() == expected) {
                found = true;
                break;
            }
        }
        assertTrue("hover state layer must paint ON_PRIMARY at 0.08 alpha: " + ctx.recording().of(Op.ROUNDED_RECT),
                found);
    }

    @Test
    public void pressTriggersRippleInStoreNotOnButtonInstance() {
        // Press inside button.
        ctx.setInput(pointer(30f, 15f, MaterialButton.PRIMARY_BUTTON));
        Component.Result r1 = button.render(ctx, 0f, 0f, 100f, 40f);
        assertTrue(r1.pressed());
        assertTrue(r1.hovered());

        WidgetId rippleId = ctx.childId("ripple");
        RippleState ripple = ctx.fakeStore().get(rippleId);
        assertTrue("ripple state must be in UiStateStore", ripple != null);
        assertTrue(ripple.holding());
        assertEquals(30f, ripple.originX(), 0.01f);
        assertEquals(15f, ripple.originY(), 0.01f);

        // Rebuild a NEW button instance — state must still be in the store.
        MaterialButton other = new MaterialButton("Save", MaterialButton.Variant.FILLED);
        ctx.recording().clear();
        other.render(ctx, 0f, 0f, 100f, 40f);
        RippleState same = ctx.fakeStore().get(rippleId);
        assertTrue(same.holding());
        assertTrue("same store entry must survive new component instance", same == ripple);
    }

    @Test
    public void clickOnReleaseInside() {
        // Down.
        ctx.setInput(pointer(40f, 20f, MaterialButton.PRIMARY_BUTTON));
        Component.Result down = button.render(ctx, 0f, 0f, 100f, 40f);
        assertTrue(down.pressed());
        assertFalse(down.clicked());

        // Up inside.
        ctx.setInput(pointer(40f, 20f, 0));
        Component.Result up = button.render(ctx, 0f, 0f, 100f, 40f);
        assertTrue(up.clicked());
        assertFalse(up.pressed());
    }

    @Test
    public void noClickWhenReleasedOutside() {
        ctx.setInput(pointer(40f, 20f, MaterialButton.PRIMARY_BUTTON));
        button.render(ctx, 0f, 0f, 100f, 40f);

        ctx.setInput(pointer(400f, 400f, 0));
        Component.Result up = button.render(ctx, 0f, 0f, 100f, 40f);
        assertFalse(up.clicked());
    }

    @Test
    public void tonalUsesPrimaryContainerColors() {
        MaterialButton tonal = new MaterialButton("Go", MaterialButton.Variant.TONAL);
        tonal.render(ctx, 0f, 0f, 80f, 40f);
        Call container = ctx.recording().of(Op.ROUNDED_RECT).get(0);
        assertEquals(FakeMdcTheme.PRIMARY_CONTAINER, container.argb());

        Call text = ctx.recording().of(Op.TEXT).get(0);
        assertEquals(FakeMdcTheme.ON_PRIMARY_CONTAINER, text.argb());
    }

    @Test
    public void outlinedStrokesOutlineColorNoFillPrimary() {
        MaterialButton outlined = new MaterialButton("Edit", MaterialButton.Variant.OUTLINED);
        outlined.render(ctx, 0f, 0f, 80f, 40f);

        assertEquals(0, countArgb(Op.ROUNDED_RECT, FakeMdcTheme.PRIMARY));
        assertFalse(ctx.recording().of(Op.RECT_STROKE).isEmpty());
        Call stroke = ctx.recording().of(Op.RECT_STROKE).get(0);
        assertEquals(FakeMdcTheme.OUTLINE, stroke.argb());

        Call text = ctx.recording().of(Op.TEXT).get(0);
        assertEquals(FakeMdcTheme.PRIMARY, text.argb());
    }

    @Test
    public void textVariantDrawsNoContainerFill() {
        MaterialButton textBtn = new MaterialButton("More", MaterialButton.Variant.TEXT);
        textBtn.render(ctx, 0f, 0f, 80f, 40f);
        // Only state-layer/ripple could add rounded rects; idle -> none for container.
        // Idle TEXT: no fill container, so zero rounded rects at rest.
        assertEquals(0, ctx.recording().count(Op.ROUNDED_RECT));
        assertEquals(1, ctx.recording().count(Op.TEXT));
    }

    @Test
    public void disabledDoesNotHoverOrClick() {
        MaterialButton dis = new MaterialButton("Nope", MaterialButton.Variant.FILLED, false);
        ctx.setInput(pointer(10f, 10f, MaterialButton.PRIMARY_BUTTON));
        Component.Result r = dis.render(ctx, 0f, 0f, 100f, 40f);
        assertFalse(r.hovered());
        assertFalse(r.pressed());
        assertFalse(r.clicked());
    }

    @Test
    public void pushClipUsedAroundStateOverlays() {
        button.render(ctx, 0f, 0f, 100f, 40f);
        assertEquals(1, ctx.recording().count(Op.PUSH_CLIP));
        assertEquals(1, ctx.recording().count(Op.POP_CLIP));
    }

    private static FrameInput pointer(float x, float y, int buttonMask) {
        return new FrameInput(x, y, buttonMask, 0f, 0f, List.of(), List.of());
    }

    private int countArgb(Op op, int argb) {
        int n = 0;
        for (Call c : ctx.recording().of(op)) {
            if (c.argb() == argb) {
                n++;
            }
        }
        return n;
    }
}
