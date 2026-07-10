package net.marcloud.mcp.core.memory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Durable, thread-safe experience store, persisted as JSON (gson). Loaded at
 * startup, saved on every write, so the AI's accumulated knowledge survives
 * restarts — the "growth / 神话 memory" pillar (JARVIS-1-style experiences,
 * Voyager-style retrievable notes).
 *
 * <p>Search is simple case-insensitive substring matching over title/content/
 * tags — adequate for a dev tool; a vector index can replace it later without
 * changing the tool surface.
 */
public final class MemoryStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final List<MemoryEntry> entries = new ArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final Object lock = new Object();

    public MemoryStore(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        synchronized (lock) {
            entries.clear();
            if (!Files.exists(file)) {
                return;
            }
            try (Reader r = Files.newBufferedReader(file)) {
                List<MemoryEntry> loaded = GSON.fromJson(r,
                        new TypeToken<List<MemoryEntry>>() { }.getType());
                if (loaded != null) {
                    entries.addAll(loaded);
                }
            } catch (Exception e) {
                System.err.println("[MCP Core] failed to load memory (" + file + "): " + e);
            }
            // Advance the id sequence past any loaded numeric ids.
            int max = 0;
            for (MemoryEntry e : entries) {
                try {
                    max = Math.max(max, Integer.parseInt(e.id().replaceAll("\\D", "")));
                } catch (RuntimeException ignored) {
                }
            }
            seq.set(max);
        }
    }

    private void save() {
        synchronized (lock) {
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                try (Writer w = Files.newBufferedWriter(file)) {
                    GSON.toJson(new ArrayList<>(entries), w);
                }
            } catch (IOException e) {
                System.err.println("[MCP Core] failed to save memory (" + file + "): " + e);
            }
        }
    }

    /** Add an experience; returns the assigned id. */
    public String write(String title, String content, List<String> tags) {
        MemoryEntry e;
        synchronized (lock) {
            String id = "m" + seq.incrementAndGet();
            e = new MemoryEntry(id, title, content,
                    tags == null ? List.of() : List.copyOf(tags), System.currentTimeMillis());
            entries.add(e);
            save();
        }
        return e.id();
    }

    /** All entries whose title/content/tags contain {@code query} (all if blank). */
    public List<MemoryEntry> search(String query, int limit) {
        synchronized (lock) {
            List<MemoryEntry> out = new ArrayList<>();
            // newest first
            for (int i = entries.size() - 1; i >= 0 && out.size() < limit; i--) {
                MemoryEntry e = entries.get(i);
                if (e.matches(query)) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    /** Delete by id; true if removed. */
    public boolean delete(String id) {
        synchronized (lock) {
            boolean removed = entries.removeIf(e -> e.id().equals(id));
            if (removed) {
                save();
            }
            return removed;
        }
    }

    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }
}
