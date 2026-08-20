package net.marcloud.mcp.core.drivers.act;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared parser for the maps {@code act_set} and {@code act_plan} accept. One
 * implementation so a new interact kind or look flag cannot land on one tool and
 * stay invisible on the other.
 */
public final class ActIntentParser {

    private ActIntentParser() {
    }

    /** The Map under key {@code k}, or null if absent / not a map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapArg(Map<String, Object> a, String k) {
        Object v = (a == null) ? null : a.get(k);
        return (v instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
    }

    /** A numeric array argument, or null when absent or not a list of numbers. */
    public static double[] doublesArg(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (!(v instanceof List<?> l) || l.isEmpty()) {
            return null;
        }
        double[] out = new double[l.size()];
        for (int i = 0; i < l.size(); i++) {
            if (!(l.get(i) instanceof Number n)) {
                return null;
            }
            out[i] = n.doubleValue();
        }
        return out;
    }

    /**
     * One MOVE-slot intent from an {@code act_set}/{@code act_plan} {@code move} map:
     * {@code route}, {@code to}, or raw axes. {@code route} and {@code to} together
     * are refused rather than guessed.
     */
    public static ActIntent parseMoveSlot(Map<String, Object> move) {
        double[] to = doublesArg(move, "to");
        double[] route = doublesArg(move, "route");
        if (route != null && to != null) {
            throw new IllegalArgumentException("give either 'to' (walk straight toward a point) or "
                    + "'route' (reach a block, planning around obstacles and placing blocks if "
                    + "needed), not both: they are two answers to the same question and the MOVE "
                    + "slot holds one intent");
        }
        if (route != null && route.length < 3) {
            throw new IllegalArgumentException("'route' needs three block coordinates [x,y,z]; a "
                    + "route to a half-specified block is not a request that can be honoured");
        }
        if (route != null) {
            int budget = intArg(move, "blockBudget", RouteIntent.DEFAULT_BLOCK_BUDGET);
            if (budget < 0) {
                throw new IllegalArgumentException("'blockBudget' must not be negative: " + budget);
            }
            return new RouteIntent((int) Math.floor(route[0]),
                    (int) Math.floor(route[1]), (int) Math.floor(route[2]), budget);
        }
        if (to != null) {
            return new NavIntent(to[0], to.length > 1 ? to[1] : 0,
                    to.length > 2 ? to[2] : 0, intArg(move, "timeoutTicks", 0));
        }
        return parseMove(move);
    }

    public static MoveIntent parseMove(Map<String, Object> m) {
        return new MoveIntent(
                floatArg(m, "forward", 0f),
                floatArg(m, "strafe", 0f),
                boolArg(m, "jump", false),
                boolArg(m, "sneak", false),
                boolArg(m, "sprint", false),
                intArg(m, "durationTicks", 0));
    }

    public static LookIntent parseLook(Map<String, Object> m) {
        String modeStr = strArg(m, "mode");
        String mode = modeStr == null ? "set" : modeStr.trim().toLowerCase(Locale.ROOT);
        float slew = floatArg(m, "slewDegPerTick", 0f);
        boolean track = boolArg(m, "track", false);
        int durationTicks = intArg(m, "durationTicks", 0);
        if (durationTicks < 0) {
            throw new IllegalArgumentException("act_set look 'durationTicks' must be >= 0 (0 = until "
                    + "cancelled or replaced), got " + durationTicks);
        }
        if (durationTicks > 0 && !track) {
            throw new IllegalArgumentException("act_set look 'durationTicks' only applies with "
                    + "'track':true -- without tracking the aim ends as soon as it reaches the "
                    + "target, so a duration would be accepted and never used");
        }
        switch (mode) {
            case "set":
                return track
                        ? LookIntent.holdSet(floatArg(m, "yaw", 0f), floatArg(m, "pitch", 0f), slew,
                                durationTicks)
                        : LookIntent.set(floatArg(m, "yaw", 0f), floatArg(m, "pitch", 0f), slew);
            case "look_at": {
                int[] block = intTriple(m.get("block"));
                if (block != null) {
                    return track
                            ? LookIntent.trackBlock(block[0], block[1], block[2], slew, durationTicks)
                            : LookIntent.lookAtBlock(block[0], block[1], block[2], slew);
                }
                int entityId = intArg(m, "entityId", -1);
                if (entityId >= 0) {
                    return track
                            ? LookIntent.trackEntity(entityId, slew, durationTicks)
                            : LookIntent.lookAtEntity(entityId, slew);
                }
                throw new IllegalArgumentException(
                        "act_set look mode 'look_at' needs a 'block':[x,y,z] or a non-negative 'entityId'");
            }
            default:
                throw new IllegalArgumentException(
                        "act_set look 'mode' must be 'set' or 'look_at', got '" + modeStr + "'");
        }
    }

    public static InteractIntent parseInteract(Map<String, Object> m) {
        String kindStr = strArg(m, "kind");
        if (kindStr == null) {
            throw new IllegalArgumentException("act_set interact needs a 'kind'");
        }
        String kind = kindStr.trim().toLowerCase(Locale.ROOT);
        switch (kind) {
            case "dig": {
                int[] b = requireBlock(m, "dig");
                return InteractIntent.dig(b[0], b[1], b[2], intArg(m, "face", -1));
            }
            case "use":
                return InteractIntent.useInAir();
            case "place": {
                int[] b = requireBlock(m, "place");
                return InteractIntent.place(b[0], b[1], b[2], intArg(m, "face", -1),
                        floatArg(m, "hitX", 0f), floatArg(m, "hitY", 0f), floatArg(m, "hitZ", 0f));
            }
            case "attack": {
                int entityId = intArg(m, "entityId", -1);
                if (entityId < 0) {
                    throw new IllegalArgumentException("act_set interact 'attack' needs a non-negative 'entityId'");
                }
                return InteractIntent.attack(entityId);
            }
            case "hotbar": {
                int slot = intArg(m, "hotbarSlot", -1);
                if (slot < 0 || slot > 8) {
                    throw new IllegalArgumentException("act_set interact 'hotbar' needs 'hotbarSlot' 0-8");
                }
                return InteractIntent.hotbar(slot);
            }
            case "hold": {
                int holdTicks = intArg(m, "holdTicks", 0);
                if (holdTicks < 0) {
                    throw new IllegalArgumentException(
                            "act_set interact 'hold' needs 'holdTicks' >= 0, got " + holdTicks);
                }
                return holdTicks > 0
                        ? InteractIntent.holdThenRelease(holdTicks)
                        : InteractIntent.holdUntilDone();
            }
            default:
                throw new IllegalArgumentException(
                        "act_set interact 'kind' must be one of dig|use|place|attack|hotbar|hold, "
                                + "got '" + kindStr + "'");
        }
    }

    static float floatArg(Map<String, Object> a, String k, float fallback) {
        Object v = (a == null) ? null : a.get(k);
        if (v instanceof Number n) {
            return n.floatValue();
        }
        if (v != null) {
            try {
                return Float.parseFloat(v.toString());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    static int intArg(Map<String, Object> a, String k, int fallback) {
        Object v = (a == null) ? null : a.get(k);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    static boolean boolArg(Map<String, Object> a, String k, boolean fallback) {
        Object v = (a == null) ? null : a.get(k);
        if (v instanceof Boolean b) {
            return b;
        }
        return v == null ? fallback : Boolean.parseBoolean(v.toString());
    }

    static String strArg(Map<String, Object> a, String k) {
        Object v = (a == null) ? null : a.get(k);
        return v == null ? null : v.toString();
    }

    static int[] intTriple(Object v) {
        if (!(v instanceof List<?> l) || l.size() != 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!(l.get(i) instanceof Number n)) {
                return null;
            }
            out[i] = n.intValue();
        }
        return out;
    }

    private static int[] requireBlock(Map<String, Object> m, String kind) {
        int[] b = intTriple(m.get("block"));
        if (b == null) {
            throw new IllegalArgumentException(
                    "act_set interact '" + kind + "' needs a 'block':[x,y,z]");
        }
        return b;
    }
}
