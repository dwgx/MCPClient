package net.marcloud.mcp.dwm.qml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.marcloud.mcp.dwm.ui.LiveState;

/**
 * The single object a QML scene sees as {@code Dwm}. Everything the UI knows about the running
 * client comes through here.
 *
 * <p>One object rather than several because qml4j resolves a context property as a free identifier
 * in a binding, so each one is a name the scene can shadow by accident. A single namespace also
 * makes the UI's whole dependency on the host readable in one file — which matters for a module
 * whose contract is about what it CANNOT reach.
 *
 * <p><b>Every method must be cheap and must not throw.</b> These are called from QML bindings, which
 * are evaluated during layout on the render thread, potentially several times per frame. They
 * delegate to {@link LiveState}, which returns empty results rather than throwing when core or board
 * is absent, so a page renders an "unavailable" state instead of faulting the frame.
 *
 * <p><b>Returns JDK types only.</b> A binding reads {@code Dwm.chips()[0].name} directly off a
 * {@code List<Map<String,String>>}, verified against qml4j 0.2.27 — so no wrapper types are needed
 * and none are introduced, keeping this class free of anything the SPI could not express.
 */
public final class DwmContext {

    /** The name QML binds this under. Referenced by the scenes as {@code Dwm.*}. */
    public static final String NAME = "Dwm";

    /**
     * Rows of the kernel's security posture, as {@code [{label, value}, ...]}.
     *
     * <p>Flattened from core's ordered map into a list of two-key maps because QML has no ordered-map
     * iteration and a Repeater wants an indexable model. Order is preserved, which is the point:
     * core emits the rows in a deliberate sequence and the UI should not resort them.
     */
    public List<Map<String, String>> kernelRows() {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : LiveState.kernelState().entrySet()) {
            Map<String, String> row = new java.util.LinkedHashMap<>();
            row.put("label", e.getKey());
            row.put("value", e.getValue());
            rows.add(row);
        }
        return rows;
    }

    /** True when core has published its posture, so a page can say so instead of showing blanks. */
    public boolean hasKernel() {
        return LiveState.hasKernelState();
    }

    /**
     * The live board chip roster: {@code [{id, name, category, enabled}, ...]}.
     *
     * <p>{@code enabled} is the string "true"/"false", because that is what board's port projects
     * and converting it here would add a second representation to keep in step.
     */
    public List<Map<String, String>> chips() {
        return LiveState.chipRoster();
    }

    public boolean hasChips() {
        return LiveState.hasChipRoster();
    }

    /**
     * Toggle a chip by id and report its state afterwards.
     *
     * <p>The one write the UI can perform. Board marshals it onto the game thread inside its own
     * port, so a chip that touches live game state is enabled where that is legal — not here, on the
     * render thread, mid-input-dispatch.
     */
    public boolean toggleChip(String id) {
        return LiveState.toggleChip(id);
    }

    /**
     * A short description of what the UI is attached to, for the home page.
     *
     * <p>Deliberately assembled from what is actually present rather than hardcoded, so the home
     * page tells the truth when run without core or without board.
     */
    public String attachment() {
        boolean kernel = LiveState.hasKernelState();
        boolean chips = LiveState.hasChipRoster();
        if (kernel && chips) {
            return "Attached to the kernel and the board.";
        }
        if (kernel) {
            return "Attached to the kernel; no board roster published.";
        }
        if (chips) {
            return "Attached to the board; the kernel published no state.";
        }
        return "Running standalone - neither the kernel nor the board is present.";
    }
}
