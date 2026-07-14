package net.marcloud.mcp.dwm.component.material;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.layout.Column;
import net.marcloud.mcp.dwm.component.layout.Padding;

/**
 * Teeth for the new MD3 container/content components (Card, ListItem, Text) composed
 * with the layout primitives. Uses the package's {@link FakeComponentContext} recording
 * draw context, so we assert real draw calls happen (non-vacuous) and measure math holds.
 */
public class CardListTest {

    @Test
    public void listItemMeasuresOneVsTwoLineHeights() {
        FakeComponentContext ctx = new FakeComponentContext("root");
        Component.Size one = new MaterialListItem("Headline").measure(ctx);
        Component.Size two = new MaterialListItem("Headline", "Supporting").measure(ctx);
        assertEquals("one-line MD3 height", MaterialListItem.ONE_LINE_DP, one.height(), 0.001f);
        assertEquals("two-line MD3 height", MaterialListItem.TWO_LINE_DP, two.height(), 0.001f);
        assertTrue("width includes 2*16 padding", one.width() >= MaterialListItem.PAD_H_DP * 2f);
    }

    @Test
    public void listItemDrawsHeadlineText() {
        FakeComponentContext ctx = new FakeComponentContext("root");
        new MaterialListItem("Privilege L4").render(ctx, 0f, 0f, 200f, 56f);
        assertTrue("list item drew its headline text",
                drawnText(ctx).stream().anyMatch(t -> t.contains("Privilege L4")));
    }

    @Test
    public void cardDrawsRoundedSurfaceAndClipsChild() {
        FakeComponentContext ctx = new FakeComponentContext("root");
        MaterialCard card = new MaterialCard(MaterialCard.Variant.OUTLINED,
                new MaterialListItem("Body"));
        card.render(ctx, 10f, 10f, 180f, 56f);
        RecordingDrawContext rec = ctx.recording();
        assertTrue("card drew a rounded surface", rec.count(RecordingDrawContext.Op.ROUNDED_RECT) >= 1);
        assertTrue("outlined card drew a stroke", rec.count(RecordingDrawContext.Op.RECT_STROKE) >= 1);
        int pushes = rec.count(RecordingDrawContext.Op.PUSH_CLIP);
        int pops = rec.count(RecordingDrawContext.Op.POP_CLIP);
        assertTrue("card clipped its child (pushClip)", pushes >= 1);
        assertEquals("clip was balanced", pushes, pops);
    }

    @Test
    public void cardOfColumnOfListItemsStacksAndRenders() {
        FakeComponentContext ctx = new FakeComponentContext("root");
        Component ui = new MaterialCard(Padding.all(8f, new Column(4f,
                new MaterialListItem("L1 P-SECURE", "enabled"),
                new MaterialListItem("L4 Capability", "strict"),
                new MaterialListItem("L6 Handles", "gated"))));
        Component.Size s = ui.measure(ctx);
        // 3 two-line items (72 each) + 2 gaps(4) + 2*8 padding = 216 + 8 + 16 = 240.
        assertEquals(3 * MaterialListItem.TWO_LINE_DP + 2 * 4f + 2 * 8f, s.height(), 0.001f);
        ui.render(ctx, 0f, 0f, s.width(), s.height());
        var texts = drawnText(ctx);
        assertTrue("all three headlines drawn",
                texts.stream().anyMatch(t -> t.contains("P-SECURE"))
                        && texts.stream().anyMatch(t -> t.contains("Capability"))
                        && texts.stream().anyMatch(t -> t.contains("Handles")));
    }

    /** Pull the string of every TEXT draw call recorded. */
    private static java.util.List<String> drawnText(FakeComponentContext ctx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (RecordingDrawContext.Call c : ctx.recording().of(RecordingDrawContext.Op.TEXT)) {
            if (c.extra() instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }
}
