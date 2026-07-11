package net.marcloud.mcp.core.drivers.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.io.http.Json;

/**
 * An immutable, structured view of the WHOLE clickable GUI at one instant: the
 * open screen, its viewport geometry, and every addressable element the user
 * could click. This is the payload the MCP tool layer hands to the LLM; the LLM
 * replies with an element {@link GuiElement#id() id} and the server drives the
 * real handler at that element's {@link GuiElement#clickPoint() click-point}.
 *
 * <p>{@link #epoch} is monotonic and bumped by {@link GuiSnapshotService} when
 * the open screen's IDENTITY changes; the lead uses it (with {@link #fingerprint})
 * to reject actions issued against a stale snapshot.
 *
 * @param epoch      monotonic screen-identity counter at capture time
 * @param screen     simpleName of the open {@code GuiScreen}, or null if none open
 * @param inWorld    whether the player is in a world
 * @param isContainer whether the open screen is a {@code GuiContainer} (has slots)
 * @param title      human-readable screen title (best effort; may equal screen)
 * @param viewport   screen/framebuffer geometry for overlay mapping
 * @param elements   every extracted clickable element
 * @param fingerprint cheap structural signature for the stale-epoch action guard
 * @param unreadable field/read failures encountered (fail-loud-but-degrade; empty when clean)
 */
public record GuiSnapshot(
        int epoch,
        String screen,
        boolean inWorld,
        boolean isContainer,
        String title,
        Viewport viewport,
        List<GuiElement> elements,
        String fingerprint,
        List<String> unreadable) {

    public GuiSnapshot {
        elements = elements == null ? List.of() : List.copyOf(elements);
        unreadable = unreadable == null ? List.of() : List.copyOf(unreadable);
    }

    /** Ordered map view for JSON emission / the MCP tool layer. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("epoch", epoch);
        m.put("screen", screen);
        m.put("inWorld", inWorld);
        m.put("isContainer", isContainer);
        m.put("title", title);
        m.put("viewport", viewport == null ? null : viewport.toMap());
        List<Object> els = new ArrayList<>(elements.size());
        for (GuiElement e : elements) {
            els.add(e.toMap());
        }
        m.put("elements", els);
        m.put("fingerprint", fingerprint);
        m.put("unreadable", unreadable);
        return m;
    }

    /** Serialize to JSON using Core's dependency-free writer. */
    public String toJson() {
        return Json.write(toMap());
    }

    /** Convenience: number of elements of a given {@link GuiElement} kind. */
    public long countKind(String kind) {
        return elements.stream().filter(e -> e.kind().equals(kind)).count();
    }
}
