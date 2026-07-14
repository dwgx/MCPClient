package net.marcloud.mcp.dwm.component;

import net.marcloud.mcp.dwm.component.layout.Column;
import net.marcloud.mcp.dwm.component.layout.Padding;
import net.marcloud.mcp.dwm.component.material.MaterialButton;
import net.marcloud.mcp.dwm.component.material.MaterialCard;
import net.marcloud.mcp.dwm.component.material.MaterialListItem;
import net.marcloud.mcp.dwm.component.material.MaterialText;
import net.marcloud.mcp.dwm.theme.MdcTheme.ColorRole;
import net.marcloud.mcp.dwm.theme.MdcTheme.TypeRole;

/**
 * A real MD3 demo panel exercising the whole framework — the SAME tree rendered by every
 * backend (gl / imgui / skiko) so a screenshot proves layout + card + list + text +
 * button all render consistently. Shaped like the eventual kernel-state surface: a titled
 * card with a list of "layer: status" rows and an action button.
 *
 * <p>Composed purely from DWM components + layout primitives (no backend types), placed
 * top-left at a fixed offset. This is the shared demo root the {@code *UiEntry} classes
 * use instead of hand-rolling their own, so all three backends render identically.
 */
public final class DemoPanel implements Component {

    private final Component content;

    public DemoPanel() {
        // Title + a list of kernel-ish state rows + a filled action button, in a card.
        Component list = new Column(2f,
                new MaterialListItem("L1 P-SECURE", "enabled"),
                new MaterialListItem("L4 Capability", "strict"),
                new MaterialListItem("L6 Object Handles", "gated"),
                new MaterialListItem("MCP facade", "127.0.0.1:1337"));
        Component body = new Column(8f,
                new MaterialText("DWM Kernel", TypeRole.TITLE_MEDIUM, ColorRole.ON_SURFACE),
                list,
                new MaterialButton("Refresh", MaterialButton.Variant.FILLED));
        this.content = new MaterialCard(MaterialCard.Variant.ELEVATED, Padding.all(16f, body));
    }

    @Override
    public Result render(ComponentContext ctx, float x, float y, float w, float h) {
        Size s = content.measure(ctx);
        // Top-left with a 16dp margin, at the panel's natural size.
        return content.render(ctx, x + 16f, y + 16f, s.width(), s.height());
    }

    @Override
    public Size measure(ComponentContext ctx) {
        return content.measure(ctx);
    }
}
