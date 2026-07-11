package net.marcloud.mcp.core.ob;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * L6 object-handle access rights, modelled as NT-style bit flags. A handle's
 * granted rights are a frozen bitmask; every handle operation asserts the
 * needed right is a {@link #subset(int, int) subset} of the frozen mask — the
 * core TOCTOU (time-of-check/time-of-use) guarantee, since a downward-only mask
 * can never be widened after {@code open()}.
 *
 * <ul>
 *   <li>{@code READ} — read locals / field value / channel contents.</li>
 *   <li>{@code WRITE} — set a local / field / force early return / write channel.</li>
 *   <li>{@code REDEFINE} — retransform a class / open a module package.</li>
 *   <li>{@code EXECUTE} — invoke a method / suspend-pop-step a thread.</li>
 *   <li>{@code DELETE} — uninstall / close a channel / revert a hook.</li>
 * </ul>
 */
public enum ObAccessMask {
    READ(1),
    WRITE(1 << 1),
    REDEFINE(1 << 2),
    EXECUTE(1 << 3),
    DELETE(1 << 4);

    private final int bit;

    ObAccessMask(int b) {
        this.bit = b;
    }

    /** This right's single-bit value. */
    public int bit() {
        return bit;
    }

    /** OR the bits of the given rights into one mask. */
    public static int mask(ObAccessMask... rs) {
        int m = 0;
        for (ObAccessMask r : rs) {
            m |= r.bit;
        }
        return m;
    }

    /** OR the bits of a right set into one mask. */
    public static int mask(Set<ObAccessMask> rs) {
        int m = 0;
        for (ObAccessMask r : rs) {
            m |= r.bit;
        }
        return m;
    }

    /** The core L6 check: is every bit in {@code need} present in {@code have}? */
    public static boolean subset(int have, int need) {
        return (have & need) == need;
    }

    /** True if this right's bit is set in {@code mask}. */
    public boolean in(int mask) {
        return (mask & bit) != 0;
    }

    /** Decode a mask into the set of rights it grants. */
    public static EnumSet<ObAccessMask> decode(int mask) {
        EnumSet<ObAccessMask> s = EnumSet.noneOf(ObAccessMask.class);
        for (ObAccessMask r : values()) {
            if (r.in(mask)) {
                s.add(r);
            }
        }
        return s;
    }

    /** Parse "READ,WRITE" or "READ|WRITE" (whitespace tolerated); throws on an unknown token. */
    public static int parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return 0;
        }
        int m = 0;
        for (String t : csv.split("[,|\\s]+")) {
            if (t.isBlank()) {
                continue;
            }
            m |= valueOf(t.trim().toUpperCase(Locale.ROOT)).bit;
        }
        return m;
    }

    /** Render a mask as its decoded right set, for audit reasons. */
    public static String render(int mask) {
        return decode(mask).toString();
    }
}
