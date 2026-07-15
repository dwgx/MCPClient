package net.marcloud.mcp.core.flt.seam.summarize;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Resolves a {@link PacketSummarizer} for a packet and produces its summary
 * String (PHASE P.3). Lookup: (1) exact fully-qualified class name; (2) ordered
 * {@code handles()} fallbacks (for subclasses / family matches); (3) the generic
 * default. Every summarizer call is guarded so a throwing/null summarizer degrades
 * to the next candidate and finally to the packet's simple name — this registry
 * NEVER throws and NEVER returns null, so the tap can call it inline safely.
 *
 * <p>Invoked on the Netty worker thread; registration may race startup, hence the
 * concurrent collections. The hot path is a single {@code HashMap.get}.
 */
public final class PacketSummarizerRegistry {

    private final Map<String, PacketSummarizer> exact = new ConcurrentHashMap<>();
    private final List<PacketSummarizer> fallbacks = new CopyOnWriteArrayList<>();
    private final PacketSummarizer generic;

    public PacketSummarizerRegistry(PacketSummarizer generic) {
        this.generic = generic == null ? new GenericPacketSummarizer() : generic;
    }

    /** Register a summarizer for one or more exact class names. */
    public void register(PacketSummarizer s, String... exactClassNames) {
        if (s == null || exactClassNames == null) {
            return;
        }
        for (String cn : exactClassNames) {
            if (cn != null) {
                exact.put(cn, s);
            }
        }
    }

    /** Register a {@code handles()}-driven fallback, tried in registration order. */
    public void registerFallback(PacketSummarizer s) {
        if (s != null) {
            fallbacks.add(s);
        }
    }

    /**
     * Summarize {@code packet}. Never throws, never returns null. Tries exact,
     * then fallbacks, then generic; any candidate that throws or returns null is
     * skipped.
     */
    public String summarize(Object packet) {
        if (packet == null) {
            return "null";
        }
        String fqn = packet.getClass().getName();

        PacketSummarizer ex = exact.get(fqn);
        if (ex != null) {
            String r = safeSummarize(ex, packet);
            if (r != null) {
                return r;
            }
        }
        for (PacketSummarizer f : fallbacks) {
            try {
                if (!f.handles(fqn)) {
                    continue;
                }
            } catch (Throwable t) {
                continue;
            }
            String r = safeSummarize(f, packet);
            if (r != null) {
                return r;
            }
        }
        String g = safeSummarize(generic, packet);
        return g != null ? g : simpleName(fqn);
    }

    private static String safeSummarize(PacketSummarizer s, Object packet) {
        try {
            return s.summarize(packet);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The STRUCTURED projection for {@code packet}: the ordered JSON-ready field map
     * from the resolved summarizer's {@link PacketSummarizer#project}, or {@code null}
     * if no summarizer offers one (B/C tier, or the packet is unknown). Same
     * resolution order as {@link #summarize} (exact → handles() fallback → generic)
     * and the same never-throw guard, so the tap can call it inline safely.
     *
     * <p>Returns {@code null} (not an empty map) to mean "no typed fields available"
     * so a caller can distinguish a summarizer that declines from one that projects
     * an empty object.
     */
    public Map<String, Object> projectStructured(Object packet) {
        if (packet == null) {
            return null;
        }
        String fqn = packet.getClass().getName();

        PacketSummarizer ex = exact.get(fqn);
        if (ex != null) {
            Map<String, Object> r = safeProject(ex, packet);
            if (r != null) {
                return r;
            }
        }
        for (PacketSummarizer f : fallbacks) {
            try {
                if (!f.handles(fqn)) {
                    continue;
                }
            } catch (Throwable t) {
                continue;
            }
            Map<String, Object> r = safeProject(f, packet);
            if (r != null) {
                return r;
            }
        }
        return safeProject(generic, packet);
    }

    private static Map<String, Object> safeProject(PacketSummarizer s, Object packet) {
        try {
            return s.project(packet);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String simpleName(String fqn) {
        int cut = Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('$'));
        return cut >= 0 && cut + 1 < fqn.length() ? fqn.substring(cut + 1) : fqn;
    }

    /**
     * The default registry: the P.4 high-value summarizers over the P.5 generic
     * fallback. Registered by exact 1.8.9 class name; C03 movement family via a
     * prefix fallback (its packets are nested classes of C03PacketPlayer).
     */
    public static PacketSummarizerRegistry defaults() {
        PacketSummarizerRegistry r = new PacketSummarizerRegistry(new GenericPacketSummarizer());
        HighValueSummarizers.registerInto(r);
        return r;
    }
}
