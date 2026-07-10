package net.marcloud.mcp.core;

/**
 * MCP Core entry point.
 *
 * <p>Bootstrapped after the game is up (wired in a later stage). Assembles the
 * hot-load engine, event bus, hooks, state/action layers, and the MCP server /
 * client. For now this is a skeleton that proves the module builds and runs on
 * Java 25 bytecode alongside the Java 8 game.
 */
public final class McpCore {

    /** Marker so callers/tests can confirm the module loaded and its Java level. */
    public static String banner() {
        // Uses a text block (Java 15+) to confirm this module really compiles at 25.
        return """
               MCP Core initialized (Java 25 module, running on JDK %s)
               """.formatted(Runtime.version().feature());
    }

    private McpCore() {
    }
}
