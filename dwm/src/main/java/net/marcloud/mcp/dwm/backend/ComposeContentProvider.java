package net.marcloud.mcp.dwm.backend;

/**
 * The "content provider" seam that REPLACES {@link DrawContext} for a tree-rendering
 * {@link ContentBackend}. A {@code ContentBackend} owns its own retained tree, so the
 * game side does not emit primitives into it — instead it supplies this pure-Java
 * handle naming WHICH content root the overlay should mount, and the (Kotlin) adapter
 * turns that into {@code scene.setContent { ... }}.
 *
 * <p>This is deliberately a plain-Java functional interface: it carries no Compose or
 * Skia types, so those appear in exactly ONE module (the adapter), mirroring the SPI
 * rule that GL/imgui types live in exactly one backend-adapter package. The adapter
 * holds a table of {@code @Composable} roots keyed by id and mounts the one named by
 * {@link #contentRootId()}; an unknown id resolves to the adapter's empty/default
 * root rather than throwing (degrade-to-empty, consistent with the "reflect, miss,
 * degrade" idiom).
 */
@FunctionalInterface
public interface ComposeContentProvider {

    /**
     * The id of the content root the overlay should mount (e.g. {@code "root"} for the
     * default app tree). Never null; the adapter maps an unknown id to its empty root.
     */
    String contentRootId();
}
