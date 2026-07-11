package net.marcloud.mcp.board.chips;

import java.lang.reflect.Method;

import net.marcloud.mcp.board.Chip;

/**
 * A SAMPLE "login / premium-account auth" {@link Chip} stub. It models the
 * lifecycle of an auth feature without performing any real network login:
 * {@code onLoad} captures the current in-game username (reflectively, so it is
 * headless-safe), {@code onEnable} transitions to an "authenticated" state, and
 * {@code onDisable} logs back out. A real implementation would drive an
 * authentication flow here; this stub proves the seam and the state machine.
 *
 * <p>Reflection target (vanilla 1.8.9 mappings):
 * {@code Minecraft.getMinecraft().getSession().getUsername()}. Absent (headless
 * or renamed), it falls back to {@code "offline"} without throwing.
 */
public final class LoginChip extends Chip {

    private static final String MC_CLASS = "net.minecraft.client.Minecraft";

    /** Coarse auth state for this sample; a real chip would carry a token/session. */
    public enum AuthState {
        /** Never logged in this session. */
        LOGGED_OUT,
        /** {@code onEnable} succeeded — treated as authenticated. */
        AUTHENTICATED
    }

    private volatile AuthState state = AuthState.LOGGED_OUT;
    private volatile String username = "offline";

    @Override
    public String category() {
        return "login";
    }

    @Override
    protected void onLoad() {
        username = detectUsername();
    }

    @Override
    protected void onEnable() {
        // Stub "login": no network call — flip state so the flow is observable.
        state = AuthState.AUTHENTICATED;
    }

    @Override
    protected void onDisable() {
        // Stub "logout".
        state = AuthState.LOGGED_OUT;
    }

    /** The current auth state of this sample chip. */
    public AuthState state() {
        return state;
    }

    /** {@code true} once {@link #onEnable()} has run and not been disabled. */
    public boolean isAuthenticated() {
        return state == AuthState.AUTHENTICATED;
    }

    /** The username detected at load, or {@code "offline"} when headless. */
    public String username() {
        return username;
    }

    /**
     * Reflectively read the live client's session username, or {@code "offline"}
     * if no game is present (headless/tests) or the mapping has drifted.
     */
    private String detectUsername() {
        try {
            Class<?> mc = Class.forName(MC_CLASS);
            Object instance = mc.getMethod("getMinecraft").invoke(null);
            if (instance == null) {
                return "offline";
            }
            Method getSession = mc.getMethod("getSession");
            Object session = getSession.invoke(instance);
            if (session == null) {
                return "offline";
            }
            Object name = session.getClass().getMethod("getUsername").invoke(session);
            return name == null ? "offline" : name.toString();
        } catch (Throwable e) {
            return "offline";
        }
    }
}
