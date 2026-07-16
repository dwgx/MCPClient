package net.marcloud.mcp.dwm.component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.marcloud.mcp.board.Backplane;
import net.marcloud.mcp.dwm.component.layout.Column;
import net.marcloud.mcp.dwm.component.layout.Padding;
import net.marcloud.mcp.dwm.component.material.MaterialButton;
import net.marcloud.mcp.dwm.component.material.MaterialCard;
import net.marcloud.mcp.dwm.component.material.MaterialListItem;
import net.marcloud.mcp.dwm.component.material.MaterialText;
import net.marcloud.mcp.dwm.theme.MdcTheme.ColorRole;
import net.marcloud.mcp.dwm.theme.MdcTheme.TypeRole;

/**
 * The live kernel-state overlay panel — an MD3 card of {@code label: value} rows fed by
 * core's real 7-layer security posture, the production replacement for {@link DemoPanel}'s
 * hardcoded rows. Core publishes a {@code Supplier<Map<String,String>>} to the board
 * {@link Backplane} under the {@code "kernel.state"} key; this panel looks it up and
 * rebuilds its row list from a FRESH snapshot every frame, so a runtime {@code
 * disable_privilege} / {@code revoke_capability} is reflected live in the overlay.
 *
 * <p><b>Decoupling.</b> DWM depends on board (for the neutral {@link Backplane} seam) but
 * NOT on core. The value crossing the seam is a plain {@link java.util.function.Supplier}
 * of {@link Map} of {@code String→String} — pure JDK types — so this panel loads and reads
 * kernel state without a single core import. Same "reflect, miss, degrade" idiom the peer
 * bridges use.
 *
 * <p><b>Degrade-to-placeholder.</b> If board has no supplier registered (core absent /
 * not started), or the supplier throws, the panel renders a single "kernel: offline" row
 * instead of crashing — a bad/absent data source must never break the render thread.
 */
public final class KernelStatePanel implements Component {

    private static final String TITLE = "MCP Kernel";
    private static final String OFFLINE_KEY = "kernel";
    private static final String OFFLINE_VALUE = "offline";

    @Override
    public Result render(ComponentContext ctx, float x, float y, float w, float h) {
        Component content = build();
        Size s = content.measure(ctx);
        // Top-left with a 16dp margin, at the panel's natural size (mirrors DemoPanel).
        return content.render(ctx, x + 16f, y + 16f, s.width(), s.height());
    }

    @Override
    public Size measure(ComponentContext ctx) {
        return build().measure(ctx);
    }

    /**
     * Build the card tree from a fresh kernel-state snapshot. Rebuilt each call so the
     * rows track live posture; cheap (a handful of leaf components, no GL, no allocation
     * of retained state — animation state lives in the store keyed by id, not here).
     */
    private Component build() {
        Map<String, String> state = readSnapshot();

        List<Component> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : state.entrySet()) {
            rows.add(new MaterialListItem(e.getKey(), e.getValue()));
        }
        Component list = new Column(2f, rows);

        Component body = new Column(8f,
                new MaterialText(TITLE, TypeRole.TITLE_MEDIUM, ColorRole.ON_SURFACE),
                list,
                new MaterialButton("Refresh", MaterialButton.Variant.FILLED));
        return new MaterialCard(MaterialCard.Variant.ELEVATED, Padding.all(16f, body));
    }

    /**
     * Read the live kernel-state map off the Backplane. Returns a single offline row when
     * no supplier is registered, the registered object is not a supplier, or the supplier
     * (or its map) is null/throws — never propagates a fault to the caller.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> readSnapshot() {
        try {
            Object registered = Backplane.find("kernel.state");
            if (registered instanceof java.util.function.Supplier<?> supplier) {
                Object result = supplier.get();
                if (result instanceof Map<?, ?> map && !map.isEmpty()) {
                    // Copy into an ordered map of String→String (values already are).
                    Map<String, String> out = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                    return out;
                }
            }
        } catch (Throwable t) {
            // fall through to the offline placeholder
        }
        Map<String, String> offline = new LinkedHashMap<>();
        offline.put(OFFLINE_KEY, OFFLINE_VALUE);
        return offline;
    }
}
