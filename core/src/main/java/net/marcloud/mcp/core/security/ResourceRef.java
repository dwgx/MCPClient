package net.marcloud.mcp.core.security;

import java.util.Locale;

/**
 * A parsed reference to a live in-JVM resource that an L6 {@link ObjectHandle}
 * can be opened against. The scheme names the kind of resource; {@link
 * #allowableRights()} bounds which rights a handle to that scheme may legally
 * freeze, so {@code open()} rejects a desired mask that exceeds the scheme's
 * ceiling before any resource is resolved.
 *
 * <p>Textual form is {@code "<scheme>:<target>"}, e.g.
 * {@code "class:net.minecraft.X"}, {@code "field:player#health"},
 * {@code "method:owner#name"}, {@code "channel:<id>"}, {@code "thread:<name|id>"},
 * {@code "frame:<thread>:<depth>"}, {@code "module:java.base/jdk.internal.misc"}.
 *
 * @param scheme the resource kind
 * @param target the scheme-specific target string (never blank)
 */
public record ResourceRef(Scheme scheme, String target) {

    /** The seven L6 resource kinds. */
    public enum Scheme { CLASS, FIELD, METHOD, CHANNEL, THREAD, FRAME, MODULE }

    public ResourceRef {
        if (scheme == null) {
            throw new IllegalArgumentException("null scheme");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("blank target");
        }
    }

    /** Parse {@code "<scheme>:<target>"}; throws on a missing prefix or unknown scheme. */
    public static ResourceRef parse(String s) {
        if (s == null) {
            throw new IllegalArgumentException("null ref");
        }
        int c = s.indexOf(':');
        if (c < 0) {
            throw new IllegalArgumentException("missing scheme prefix: " + s);
        }
        String p = s.substring(0, c).trim().toUpperCase(Locale.ROOT);
        Scheme sc;
        try {
            sc = Scheme.valueOf(p);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown scheme '" + p + "' in " + s);
        }
        return new ResourceRef(sc, s.substring(c + 1).trim());
    }

    /** The scheme prefix in lower case (the textual scheme token). */
    public String prefix() {
        return scheme.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The rights a handle to this scheme may legally freeze. {@code open()}
     * rejects any desired mask not a subset of this.
     * <ul>
     *   <li>CLASS → READ|REDEFINE</li>
     *   <li>FIELD → READ|WRITE</li>
     *   <li>METHOD → EXECUTE</li>
     *   <li>CHANNEL → READ|WRITE|DELETE</li>
     *   <li>THREAD → READ|WRITE|EXECUTE (locals-read / set-local+force-return / suspend-pop-step)</li>
     *   <li>FRAME → READ|WRITE (locals)</li>
     *   <li>MODULE → REDEFINE (open package)</li>
     * </ul>
     */
    public int allowableRights() {
        return switch (scheme) {
            case CLASS   -> AccessRight.mask(AccessRight.READ, AccessRight.REDEFINE);
            case FIELD   -> AccessRight.mask(AccessRight.READ, AccessRight.WRITE);
            case METHOD  -> AccessRight.EXECUTE.bit();
            case CHANNEL -> AccessRight.mask(AccessRight.READ, AccessRight.WRITE, AccessRight.DELETE);
            case THREAD  -> AccessRight.mask(AccessRight.READ, AccessRight.WRITE, AccessRight.EXECUTE);
            case FRAME   -> AccessRight.mask(AccessRight.READ, AccessRight.WRITE);
            case MODULE  -> AccessRight.REDEFINE.bit();
        };
    }
}
