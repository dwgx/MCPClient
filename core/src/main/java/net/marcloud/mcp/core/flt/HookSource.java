package net.marcloud.mcp.core.flt;

import java.util.List;

/**
 * A source of runtime hook descriptors, implemented by {@link FltManager} (the
 * fixed network hooks) and {@link FltDynamicManager} (runtime-installed hooks).
 * {@code list_hooks} aggregates all hook sources so it automatically sees both
 * the fixed network hooks and any AI-authored hot-load hooks.
 */
public interface HookSource {

    /** The live set of hooks from this source, with their installed flags. */
    List<HookInfo> hooks();

    /** Name of this source (default: simple class name) for reporting. */
    default String sourceName() {
        return getClass().getSimpleName();
    }
}
