package net.marcloud.mcp.core.se;

/**
 * L4 — named powerful operations, modeled on NT access-token privileges. A
 * privilege gates a specific dangerous <i>verb</i> (contrast L5 capability SIDs,
 * which gate access to a resource <i>class</i>). Privileges are two-state:
 * <b>granted</b> (present in the token at all) and <b>enabled</b> (currently
 * active). A privileged operation requires the privilege to be BOTH granted and
 * enabled — mirroring {@code AdjustTokenPrivileges}: holding {@code SeDebugName}
 * is not enough, it must also be enabled.
 *
 * <p>This lets a subject carry a dangerous privilege dormant and enable it only
 * for the window it is needed (least privilege in time), and lets an operator
 * disable a privilege without revoking the grant.
 */
public enum Privilege {

    SE_DEBUG_CLASS("redefine loaded-class bytecode (redefine_class / DCEVM)"),
    SE_LOAD_AGENT("load or attach a JVM agent at runtime"),
    SE_NET_RAW("craft and send raw/arbitrary packets"),
    SE_WORLD_WRITE("write shared/server-visible world or player state"),
    SE_SCREEN_CAP("capture the rendered GL framebuffer"),
    SE_CREATE_TOOL("compile, register, and roll back live tools"),
    SE_RUN_GENERATED("execute AI-authored / generated tool code (arbitrary in-proc Java)"),
    SE_GUI_INTERACT("drive the live GUI: click elements, type into fields, press keys"),
    SE_DEBUG_CONTROL("native JVMTI thread control: pause/PopFrame/breakpoint/step/locals"),
    SE_SEAM_INJECT("install runtime bytecode seams (Netty MITM / GLFW / tick)");

    private final String description;

    Privilege(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /** Parse "SE_DEBUG_CLASS" or bare "DEBUG_CLASS" (case-insensitive); null if unknown. */
    public static Privilege parse(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toUpperCase(java.util.Locale.ROOT);
        String full = v.startsWith("SE_") ? v : "SE_" + v;
        for (Privilege p : values()) {
            if (p.name().equals(full)) {
                return p;
            }
        }
        return null;
    }
}
