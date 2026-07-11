package net.marcloud.mcp.core.se;

/**
 * L3 — Mandatory Integrity Control (Windows MIC). Every subject and every
 * resource carries an integrity level; the mandatory rule is <b>no-write-up</b>:
 * a subject may modify a resource only when its own integrity is at least as
 * high as the resource's. Higher rank = more trusted. This runs independently of
 * (and in addition to) the L2 ring, exactly as MIC runs before the DACL in NT.
 *
 * <p>Mapping to this project:
 * <ul>
 *   <li><b>PROTECTED</b> — the privilege-model classes themselves (also covered
 *       by {@link SeProtectedObjects}); nothing at or below SYSTEM may write them.</li>
 *   <li><b>SYSTEM</b> — agent / Instrumentation, the hot-load engine. The
 *       dev-default subject sits here (can write everything except PROTECTED).</li>
 *   <li><b>HIGH</b> — {@code net.minecraft.*} game classes, the network connection.</li>
 *   <li><b>MEDIUM_PLUS</b> — self-modification of the tool set (create/rollback).</li>
 *   <li><b>MEDIUM</b> — live world/player/screen state; AI-authored tools.</li>
 *   <li><b>LOW</b> — the durable memory store, narrative log.</li>
 *   <li><b>UNTRUSTED</b> — nothing writable; the floor.</li>
 * </ul>
 */
public enum IntegrityLevel {

    UNTRUSTED(0, "Untrusted"),
    LOW(1, "Low"),
    MEDIUM(2, "Medium"),
    MEDIUM_PLUS(3, "MediumPlus"),
    HIGH(4, "High"),
    SYSTEM(5, "System"),
    PROTECTED(6, "Protected");

    private final int rank;
    private final String label;

    IntegrityLevel(int rank, String label) {
        this.rank = rank;
        this.label = label;
    }

    public int rank() {
        return rank;
    }

    public String label() {
        return label;
    }

    /**
     * The no-write-up predicate: true if a subject at this level may modify a
     * resource labeled {@code resource}. Equivalent to {@code this.rank >=
     * resource.rank}.
     */
    public boolean canWriteTo(IntegrityLevel resource) {
        return resource == null || this.rank >= resource.rank;
    }

    /** True if this level is at least as trusted as {@code other}. */
    public boolean dominates(IntegrityLevel other) {
        return other == null || this.rank >= other.rank;
    }

    /** Parse "SYSTEM"/"system"/"HIGH" (case-insensitive); null if unknown. */
    public static IntegrityLevel parse(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toUpperCase(java.util.Locale.ROOT);
        for (IntegrityLevel l : values()) {
            if (l.name().equals(v) || l.label.equalsIgnoreCase(s.trim())) {
                return l;
            }
        }
        return null;
    }
}
