package net.marcloud.mcp.core.flt.seam;

import java.lang.instrument.Instrumentation;
import java.util.function.LongConsumer;

/**
 * Boot-time coordinator that wires the render-frame overlay WITHOUT core depending on
 * any DWM backend module. It reflectively discovers whichever optional overlay backend
 * jar is on the game classpath — the pure-Java handwritten-GL backend ({@code dwm-gl})
 * or the imgui backend — and, if present, installs the {@link RenderFrameInjector} seam
 * and routes each frame to it.
 *
 * <p><b>Decoupling contract.</b> Core owns exactly two neutral types on this path:
 * {@link RenderBridge.RenderFrameSink} and the JDK {@link LongConsumer} the entry point
 * hands back. Each backend module (which CAN import DWM's {@code ComposeCompositor} +
 * the {@code ContentBackend} SPI, but NOT core) exposes a public static factory
 * {@code frameSink(long windowHandle)} returning a {@link LongConsumer} that drives one
 * overlay frame per call. Core reaches it by reflection only — no compile-time link — so
 * deleting every backend module leaves core compiling and the game running with no
 * overlay. See {@link #ENTRY_CLASSES} for the candidate entry points.
 *
 * <p><b>Degrade-to-absent.</b> If no entry class is on the classpath (ClassNotFound) or
 * anything throws, this is a silent no-op: no seam installed, no overlay, game
 * unaffected. Mirrors the Board/Backplane "reflect, miss, degrade" idiom.
 */
public final class RenderOverlayCoordinator {

    private static final String ENV_FLAG = "mcp.core.overlay";
    /** Which overlay backend to prefer: {@code gl} | {@code imgui}. */
    private static final String ENV_BACKEND = "mcp.core.overlay.backend";

    /**
     * Candidate reflective entry classes by backend id. Each exposes the SAME contract —
     * {@code public static java.util.function.LongConsumer frameSink(long windowHandle)}
     * — so core can drive whichever backend jar happens to be on the classpath without a
     * compile-time link to any of them. Insertion order is the default preference when no
     * explicit backend is requested: the pure-Java handwritten-GL backend first (no
     * native deps, no Kotlin), then the imgui backend (native Dear ImGui).
     */
    private static final java.util.LinkedHashMap<String, String> ENTRY_CLASSES =
            new java.util.LinkedHashMap<>();

    static {
        ENTRY_CLASSES.put("gl", "net.marcloud.mcp.dwm.gl.GlOverlayEntry");
        ENTRY_CLASSES.put("imgui", "net.marcloud.mcp.dwm.backend.imgui.ImGuiOverlayEntry");
    }

    private RenderOverlayCoordinator() {
    }

    /**
     * Discover + wire the overlay if its module is present and enabled. Off by default:
     * the overlay is an experimental live feature, so it arms only when {@code
     * -Dmcp.core.overlay=true} is set (a bad overlay must never be a surprise in a
     * normal run). Never throws.
     *
     * @param inst         the agent Instrumentation (the seam needs retransform; null → skip)
     * @param windowHandle the GLFW window handle the overlay renders against (0 if unknown)
     */
    public static void tryInstall(Instrumentation inst, long windowHandle) {
        if (!Boolean.parseBoolean(System.getProperty(ENV_FLAG, "false"))) {
            return; // opt-in only
        }
        if (inst == null || !inst.isRetransformClassesSupported()) {
            System.err.println("[MCP Overlay] Instrumentation/retransform unavailable — overlay disabled.");
            return;
        }
        LongConsumer driver = resolveDriver(windowHandle);
        if (driver == null) {
            System.err.println("[MCP Overlay] no overlay backend present on the classpath — no overlay.");
            return;
        }
        try {
            // install() wires the sink into RenderBridge AND registers the advice, so
            // the driver is live the moment the transformer is in place (the advice
            // calls RenderBridge.onRenderFrame -> our sink -> driver.accept(frame)).
            RenderFrameInjector injector = new RenderFrameInjector();
            injector.install(inst, driver::accept);
            System.err.println("[MCP Overlay] overlay armed (render-frame seam installed).");
        } catch (Throwable t) {
            System.err.println("[MCP Overlay] overlay wiring failed (disabled, game unaffected): " + t);
        }
    }

    /**
     * Resolve the overlay driver by reflection, honoring an optional
     * {@code -Dmcp.core.overlay.backend=<id>} preference. When a backend id is requested,
     * only that candidate is tried; otherwise every candidate is tried in preference
     * order and the first one present on the classpath wins. Returns null when no
     * candidate is present or none yields a valid {@link LongConsumer}. Never throws.
     *
     * <p>Package-visible so a unit test can exercise the candidate-resolution logic
     * (which entry names are tried, in what order, and the degrade-to-null contract)
     * without a live agent JVM.
     *
     * @param windowHandle the GLFW window handle passed to the backend's {@code frameSink}
     */
    static LongConsumer resolveDriver(long windowHandle) {
        String requested = System.getProperty(ENV_BACKEND);
        for (java.util.Map.Entry<String, String> candidate : ENTRY_CLASSES.entrySet()) {
            String id = candidate.getKey();
            if (requested != null && !requested.isBlank() && !requested.equalsIgnoreCase(id)) {
                continue; // an explicit backend was requested; skip the others
            }
            LongConsumer driver = tryEntry(candidate.getValue(), windowHandle);
            if (driver != null) {
                System.err.println("[MCP Overlay] selected backend '" + id + "' ("
                        + candidate.getValue() + ").");
                return driver;
            }
        }
        return null;
    }

    /**
     * Invoke {@code <entryClass>.frameSink(long)} reflectively. Returns the driver, or
     * null if the class is absent (expected when that backend jar is not on the
     * classpath) or it did not return a {@link LongConsumer}. Never throws.
     */
    private static LongConsumer tryEntry(String entryClass, long windowHandle) {
        try {
            Class<?> entry = Class.forName(entryClass);
            Object result = entry.getMethod("frameSink", long.class).invoke(null, windowHandle);
            if (result instanceof LongConsumer driver) {
                return driver;
            }
            System.err.println("[MCP Overlay] " + entryClass
                    + ".frameSink did not return a LongConsumer — skipping.");
            return null;
        } catch (ClassNotFoundException e) {
            return null; // that backend jar is not present — expected, try the next
        } catch (Throwable t) {
            System.err.println("[MCP Overlay] entry " + entryClass
                    + " failed to arm (skipping): " + t);
            return null;
        }
    }
}
