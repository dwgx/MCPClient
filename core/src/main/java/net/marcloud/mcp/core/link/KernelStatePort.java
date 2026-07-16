package net.marcloud.mcp.core.link;

import java.util.LinkedHashMap;
import java.util.Map;

import net.marcloud.mcp.core.se.CapabilitySid;
import net.marcloud.mcp.core.se.Privilege;
import net.marcloud.mcp.core.se.PrivilegeToken;
import net.marcloud.mcp.core.se.SeReferenceMonitor;
import net.marcloud.mcp.core.se.SeToken;

/**
 * The core→UI kernel-state facade — a live, reference-free snapshot of the 7-layer
 * security posture, published to the board {@code Backplane} so the DWM overlay can
 * render it WITHOUT any compile-time dependency on core. Core and the overlay module
 * are decoupled the same way core and board are: core never imports a DWM/board type,
 * the overlay never imports a core type. The only thing that crosses the seam is a
 * {@link java.util.function.Supplier} of a {@link Map} of {@code String→String} — pure
 * JDK types both sides already have.
 *
 * <p><b>Live, not a startup snapshot.</b> {@link #snapshot()} re-reads the monitor's
 * {@code currentSubject()} (which re-stamps the live clearance) and the compat engine's
 * armed set on every call, so a runtime {@code disable_privilege} / {@code
 * revoke_capability} is reflected the next frame the overlay reads it.
 *
 * <p><b>Fault isolation.</b> Every field read is guarded: a source throwing (or being
 * absent, e.g. the compat engine never ignited) yields an {@code "n/a"} value for that
 * row, never a thrown snapshot — the render thread must never break because one posture
 * source hiccuped. This mirrors the "reflect, miss, degrade" idiom the peer bridges use.
 *
 * <p>Not part of the frozen skeleton — a seam under {@code core.link}, the core-side twin
 * of {@code board.link/BoardPort}. It may grow rows over time (additive).
 */
public final class KernelStatePort {

    /** The Backplane key the overlay looks this port's supplier up by (peer-visible, stable). */
    public static final String KEY = "kernel.state";

    private static final String NA = "n/a";

    private final SeReferenceMonitor engine;

    /**
     * @param engine the live security reference monitor whose {@code currentSubject()}
     *               reflects runtime privilege/capability changes
     */
    public KernelStatePort(SeReferenceMonitor engine) {
        this.engine = engine;
    }

    /**
     * Recompute the current kernel posture as an ordered {@code label→value} map. Each
     * value is best-effort: a source that throws or is absent fills {@code "n/a"} rather
     * than failing the whole snapshot. Order is fixed (insertion order of the rows below).
     */
    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        SeToken subject = currentSubjectSafe();

        m.put("Clearance", clearance(subject));
        m.put("Integrity", integrity(subject));
        m.put("Disabled privileges", disabledPrivileges(subject));
        m.put("Revoked caps", revokedCaps(subject));
        m.put("Armed patches", armedPatches());
        m.put("MCP facade", facade());
        return m;
    }

    private SeToken currentSubjectSafe() {
        try {
            return engine == null ? null : engine.currentSubject();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String clearance(SeToken s) {
        try {
            return s == null || s.clearance() == null ? NA : s.clearance().name();
        } catch (Throwable t) {
            return NA;
        }
    }

    private static String integrity(SeToken s) {
        try {
            return s == null || s.integrity() == null ? NA : s.integrity().name();
        } catch (Throwable t) {
            return NA;
        }
    }

    /**
     * Privileges that are granted but currently DISABLED (a runtime {@code
     * disable_privilege} flips a grant's enabled bit off). "none" when every granted
     * privilege is still enabled — the dev default.
     */
    private static String disabledPrivileges(SeToken s) {
        try {
            if (s == null || s.privileges() == null) {
                return NA;
            }
            PrivilegeToken priv = s.privileges();
            StringBuilder sb = new StringBuilder();
            for (Privilege p : Privilege.values()) {
                if (priv.isGranted(p) && !priv.isEnabled(p)) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(p.name());
                }
            }
            return sb.length() == 0 ? "none" : sb.toString();
        } catch (Throwable t) {
            return NA;
        }
    }

    /**
     * Capabilities that are NOT held by the current subject (default-deny view). A
     * wildcard subject (null capability set = holds all, the dev default) reports
     * "none"; a strict subject reports the SIDs it lacks. A runtime {@code
     * revoke_capability} makes the revoked SID show up here.
     */
    private static String revokedCaps(SeToken s) {
        try {
            if (s == null) {
                return NA;
            }
            java.util.Set<CapabilitySid> held = s.capabilities();
            if (held == null) {
                return "none"; // wildcard: holds every capability
            }
            StringBuilder sb = new StringBuilder();
            for (CapabilitySid sid : CapabilitySid.values()) {
                if (!held.contains(sid)) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(sid.name());
                }
            }
            return sb.length() == 0 ? "none" : sb.toString();
        } catch (Throwable t) {
            return NA;
        }
    }

    /** Count of compat patches currently armed, or "n/a" if the engine never ignited. */
    private static String armedPatches() {
        try {
            net.marcloud.mcp.core.compat.CompatEngine e =
                    net.marcloud.mcp.core.compat.Compat.engine();
            return e == null ? NA : Integer.toString(e.armedPatchIds().size());
        } catch (Throwable t) {
            return NA;
        }
    }

    /** The REST facade bind:port, or "off" when the facade is disabled. */
    private static String facade() {
        try {
            if ("false".equalsIgnoreCase(System.getProperty("mcp.core.http", "true"))) {
                return "off";
            }
            String bind = System.getProperty("mcp.core.httpBind", "127.0.0.1");
            int port = Integer.getInteger("mcp.core.httpPort",
                    net.marcloud.mcp.core.io.http.HttpFacade.DEFAULT_PORT);
            return bind + ":" + port;
        } catch (Throwable t) {
            return NA;
        }
    }
}
