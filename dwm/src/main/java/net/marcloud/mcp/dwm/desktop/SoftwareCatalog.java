package net.marcloud.mcp.dwm.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The launcher's app catalog: the source of the software list plus the pure query/group
 * logic the Desktop renders. It is the "iGPU display logic" layer — it decides how the
 * software (chips, the CPUs) are presented, without any software knowing a UI exists.
 *
 * <p>Phase 1 is fed a fixed {@link SoftwareView} list (fake data) so the layout can be
 * built and tested headless; phase 2 swaps the source for a live {@code ChipBridge} that
 * reflects the board's chip matrix. All the filtering/grouping/pinning below is pure and
 * unit-testable regardless of the source.
 */
public final class SoftwareCatalog {

    /** List layout vs grid layout for the "All" section. */
    public enum Layout { LIST, GRID }

    private final java.util.function.Supplier<List<SoftwareView>> source;
    private final java.util.LinkedHashSet<String> pinned = new java.util.LinkedHashSet<>();
    private Layout layout = Layout.LIST;
    private String query = "";

    /**
     * Fixed-list catalog (phase 1 / tests): the software never changes after construction.
     */
    public SoftwareCatalog(List<SoftwareView> software) {
        List<SoftwareView> snapshot = software == null ? List.of() : List.copyOf(software);
        this.source = () -> snapshot;
    }

    /**
     * Live catalog (phase 2): {@code source} is re-read on every {@link #all()} so the
     * launcher reflects the board's current chip roster each frame. A supplier that throws or
     * returns null degrades to an empty list — the launcher shows nothing rather than crashing.
     *
     * @param source supplies the current software list (e.g. a {@link ChipBridge} roster)
     */
    public SoftwareCatalog(java.util.function.Supplier<List<SoftwareView>> source) {
        this.source = source == null ? List::of : source;
    }

    /** All software, unfiltered, in source order. Re-read from the source (live for phase 2). */
    public List<SoftwareView> all() {
        try {
            List<SoftwareView> current = source.get();
            return current == null ? List.of() : current;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Software matching the current {@link #query()} (name/category substring). */
    public List<SoftwareView> filtered() {
        List<SoftwareView> software = all();
        if (query.isBlank()) {
            return software;
        }
        List<SoftwareView> out = new ArrayList<>();
        for (SoftwareView v : software) {
            if (v.matches(query)) {
                out.add(v);
            }
        }
        return out;
    }

    /** Filtered software grouped by {@link SoftwareView#groupLabel()}, insertion-ordered. */
    public Map<String, List<SoftwareView>> grouped() {
        Map<String, List<SoftwareView>> groups = new LinkedHashMap<>();
        for (SoftwareView v : filtered()) {
            groups.computeIfAbsent(v.groupLabel(), k -> new ArrayList<>()).add(v);
        }
        return groups;
    }

    /** The pinned software (by chipId), in pin order, filtered by the current query. */
    public List<SoftwareView> pinnedViews() {
        List<SoftwareView> software = all();
        List<SoftwareView> out = new ArrayList<>();
        for (String id : pinned) {
            for (SoftwareView v : software) {
                if (v.chipId().equals(id) && v.matches(query)) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    public boolean isPinned(String chipId) {
        return pinned.contains(chipId);
    }

    /** Pin/unpin a software; returns the new pinned state. */
    public boolean togglePin(String chipId) {
        if (chipId == null || chipId.isBlank()) {
            return false;
        }
        if (pinned.remove(chipId)) {
            return false;
        }
        pinned.add(chipId);
        return true;
    }

    public Layout layout() {
        return layout;
    }

    public void setLayout(Layout layout) {
        if (layout != null) {
            this.layout = layout;
        }
    }

    /** Flip LIST<->GRID and return the new layout. */
    public Layout toggleLayout() {
        layout = layout == Layout.LIST ? Layout.GRID : Layout.LIST;
        return layout;
    }

    public String query() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query;
    }
}
