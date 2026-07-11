package net.marcloud.mcp.core.mm;

import net.marcloud.mcp.core.GameAccess;

/**
 * Resolves string paths like "player.capabilities" or "mc.currentScreen" into
 * live receiver objects for MmAccess field/method operations. Starts from a
 * known ROOT (mc, player, world, netHandler, networkManager) and walks dotted
 * field names via {@link MmAccess#getField}.
 *
 * <p>Grammar: {@code rootName[.field]*} where rootName is one of the GameAccess
 * accessors. E.g. "player" -> game.player(); "player.capabilities" ->
 * game.player().capabilities (via getField). Static targets use "className"
 * directly (no root walk).
 */
final class RootResolver {

    private final GameAccess game;
    // Set after MmAccess is built to avoid circular dependency
    private MmAccess deepAccess;

    RootResolver(GameAccess game) {
        this.game = game;
    }

    void setDeepAccess(MmAccess da) {
        this.deepAccess = da;
    }

    /**
     * Resolve {@code path} to a live object. E.g. "player" -> EntityPlayerSP,
     * "player.capabilities" -> PlayerCapabilities, "mc.currentScreen" ->
     * GuiScreen or null.
     *
     * @throws MmAccessException if root unknown or field not found
     */
    Object resolveReceiver(String path) {
        if (path == null || path.isBlank()) {
            throw new MmAccessException("path is blank");
        }

        String[] parts = path.split("\\.");
        String root = parts[0];

        Object current = switch (root) {
            case "mc" -> game.mc();
            case "player" -> game.player();
            case "world" -> game.world();
            case "netHandler" -> game.netHandler();
            case "networkManager" -> game.networkManager();
            default -> throw new MmAccessException("unknown root: " + root
                    + " (expected mc, player, world, netHandler, networkManager)");
        };

        if (current == null) {
            throw new MmAccessException("root " + root + " is null (not in world / not connected)");
        }

        // Walk the remaining path via getField
        for (int i = 1; i < parts.length; i++) {
            if (deepAccess == null) {
                throw new IllegalStateException("MmAccess not wired into RootResolver");
            }
            current = deepAccess.getField(current, parts[i]);
            if (current == null) {
                throw new MmAccessException("field " + parts[i] + " is null in path " + path);
            }
        }

        return current;
    }

    boolean isKnownRoot(String head) {
        return switch (head) {
            case "mc", "player", "world", "netHandler", "networkManager" -> true;
            default -> false;
        };
    }
}
