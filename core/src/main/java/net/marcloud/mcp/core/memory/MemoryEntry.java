package net.marcloud.mcp.core.memory;

import java.util.List;

/**
 * One durable experience the AI chose to remember. Structure follows JARVIS-1's
 * {task, state, plan} experience triple, generalized: a titled, tagged note the
 * AI can search and reuse across sessions. This is the "knowledge" half of the
 * Kernel's growth (the "capability" half is create_tool'd tools).
 *
 * @param id        stable id (assigned by the store)
 * @param title     short label / the situation ("kicked by anticheat on server X")
 * @param content   the lesson / plan / fact
 * @param tags      free-form tags for filtering
 * @param createdAt epoch millis
 */
public record MemoryEntry(String id,
                          String title,
                          String content,
                          List<String> tags,
                          long createdAt) {

    /** Does this entry match a free-text query (title/content/tags, case-insensitive)? */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.toLowerCase();
        if (title != null && title.toLowerCase().contains(q)) {
            return true;
        }
        if (content != null && content.toLowerCase().contains(q)) {
            return true;
        }
        if (tags != null) {
            for (String t : tags) {
                if (t != null && t.toLowerCase().contains(q)) {
                    return true;
                }
            }
        }
        return false;
    }

    public String toLine() {
        return String.format("[%s] %s  (tags: %s)%n    %s",
                id, title, tags == null ? "[]" : tags, content);
    }
}
