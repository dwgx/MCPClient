package net.marcloud.mcp.core.se;

import java.util.Locale;

/**
 * L5 — capability SIDs, modeled on Windows AppContainer capability SIDs. A
 * capability answers "may this subject touch resource class X <i>at all</i>?"
 * (a NOUN). Contrast {@link Privilege} (L4), which gates a specific dangerous
 * VERB. The model is <b>default-deny</b>: unless the subject's granted set
 * contains a tool's required SID(s), the tool is denied — regardless of ring.
 *
 * <p>A tool declares the set it requires (see {@link CapabilityCatalog}); a
 * subject holds a granted set (see {@code SubjectCapabilities} / the dev-default
 * wildcard). Each SID names one resource class and tags the C1-C8 capability it
 * belongs to, so the model is auditable.
 */
public enum CapabilitySid {

    CAP_CLASS_INTROSPECT("enumerate loaded classes + reflective metadata reads", "C1"),
    CAP_CLASS_RETRANSFORM("ByteBuddy retransform: install/uninstall/reset transformers", "C3"),
    CAP_CLASS_REDEFINE("DCEVM redefinition of loaded-class bytecode", "C4"),
    CAP_MEMORY_READ("read live object/static fields (reflection / field-watch / Unsafe)", "C2/C5"),
    CAP_MEMORY_WRITE("write live object/static fields (privateLookupIn / Unsafe)", "C5"),
    CAP_NETWORK_SEND("send packets/chat outbound on the game connection", "C8"),
    CAP_NETWORK_RECV_TAP("observe inbound/outbound packet traffic (Netty MITM / packet log)", "C2"),
    CAP_SCREEN_CAP("read the rendered framebuffer (glReadPixels)", "C2"),
    CAP_WORLD_READ("read live world/player/entity state", "C2"),
    CAP_WORLD_WRITE("mutate live world/player state / inject ticks", "C8"),
    CAP_DEBUG_CONTROL("native JVMTI thread control: pause/PopFrame/breakpoint/step/locals", "C6"),
    CAP_SEAM_INJECT("install runtime seams: Netty MITM / GLFW input / tick hook", "C8"),
    CAP_TOOL_CREATE("synthesize/compile/register/roll back live tools & hidden classes", "C7"),
    CAP_STORE_WRITE("write/delete the durable memory store", "-");

    private final String resource;
    private final String capClass;

    CapabilitySid(String resource, String capClass) {
        this.resource = resource;
        this.capClass = capClass;
    }

    public String resource() {
        return resource;
    }

    public String capClass() {
        return capClass;
    }

    public String tag() {
        return name() + " [" + capClass + "] " + resource;
    }

    /** Parse "CAP_WORLD_READ" or bare "WORLD_READ" (case-insensitive); null if unknown. */
    public static CapabilitySid parse(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toUpperCase(Locale.ROOT);
        String full = v.startsWith("CAP_") ? v : "CAP_" + v;
        for (CapabilitySid c : values()) {
            if (c.name().equals(full)) {
                return c;
            }
        }
        return null;
    }
}
