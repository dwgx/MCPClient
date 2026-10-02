package net.marcloud.mcp.core.drivers.act;

import java.util.ArrayList;
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
     * A coordinate argument that REJECTS a wrong shape instead of reporting it absent.
     *
     * <p>{@link #doublesArg} answers "is this a list of numbers?", so a {@code to} written as a
     * map — {@code {"x":-60,"y":78,"z":256}} — reads as NOT SUPPLIED. That is the defect this
     * exists to stop, and it was found on a live client, not by reading: the call returned
     * {@code accepted:true, phase ACTIVE} and then sat at {@code "moving (tick 320), moved 0.00
     * blocks"} forever, because the fallthrough built a raw {@link MoveIntent} with all-zero axes
     * and {@code durationTicks=0} — an intent that can never do anything, reports itself as
     * moving, and never terminates. The project has a rule for exactly this, stated in the
     * {@code refuseUnusableTarget} javadoc: an argument the tool cannot honour is named, not
     * dropped. Silently dropping it is how "a caller asked for a walk and got a no-op that
     * claims to be a walk".
     *
     * <p>Both shapes are ACCEPTED, because both are natural and models produce both: a list
     * {@code [x,y,z]} (what the schema documents) and a map {@code {"x":..,"y":..,"z":..}} (what
     * every other tool in this surface uses). Anything else with the key present is an error.
     */
    public static double[] coordArg(Map<String, Object> m, String key, String toolHint) {
        Object v = m == null ? null : m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> l) {
            double[] out = doublesArg(m, key);
            if (out == null) {
                throw new IllegalArgumentException("act_set move '" + key + "': "
                        + jsonPreview(v) + " is not a list of numbers. " + toolHint);
            }
            return out;
        }
        if (v instanceof Map<?, ?> mp) {
            Map<String, Object> mm = mapArg(m, key);
            double[] out = new double[]{
                requireNum(mm, key, "x"), requireNum(mm, key, "y"), requireNum(mm, key, "z")};
            return out;
        }
        throw new IllegalArgumentException("act_set move '" + key + "': " + jsonPreview(v)
                + " is neither [x,y,z] nor {\"x\":..,\"y\":..,\"z\":..}. " + toolHint);
    }

    private static double requireNum(Map<String, Object> m, String key, String axis) {
        Object v = m == null ? null : m.get(axis);
        if (!(v instanceof Number n)) {
            throw new IllegalArgumentException("act_set move '" + key + "': the coordinate object "
                    + "needs a numeric '" + axis + "' (got " + jsonPreview(v) + ")");
        }
        return n.doubleValue();
    }

    private static String jsonPreview(Object v) {
        String s = String.valueOf(v);
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }

    /** The accepted shapes, quoted in the error so a caller can fix the call in one step. */
    private static final String WALK_STRAIGHT_SHAPE =
            "Use 'walk_straight':[x,y,z] or 'walk_straight':{\"x\":..,\"y\":..,\"z\":..} to walk "
                    + "in a STRAIGHT LINE toward a point.";
    private static final String GO_TO_SHAPE =
            "Use 'go_to':[x,y,z] or 'go_to':{\"x\":..,\"y\":..,\"z\":..} to REACH a block, "
                    + "pathfinding around obstacles and placing blocks to cross gaps.";

    /**
     * The destination keys this parser used to accept, and what each is called now.
     *
     * <p>They are refused rather than aliased, and that is the whole point of the rename:
     * {@code to} reads like the safe option and is the opposite -- it walks a straight line,
     * never plans around an obstacle and never builds, so on a live client it walked the player
     * off a 30-block cliff and drowned them with no warning anywhere (audit 2.3). {@code route}
     * is the one that does the work. A name is all an agent reads before choosing, so the
     * dangerous option must not keep the shorter, safer-sounding name.
     *
     * <p>An old name that still worked would be a name that kept being chosen, and a silent
     * alias would leave the caller with no way to tell the tool had changed at all.
     */
    private static final String[][] RENAMED_MOVE_KEYS = {
        // old, new, and what the NEW name actually does -- carried per row because the two
        // descriptions are opposites, and a templated sentence reused across both rows would
        // tell a caller that 'go_to' walks a straight line. That is the exact class of lie this
        // rename exists to remove, reintroduced in the refusal.
        {"to", "walk_straight", "walks in a STRAIGHT LINE toward a point, with no pathfinding and "
                + "no building: use 'go_to' instead unless you know the line is clear"},
        {"route", "go_to", "REACHES a block, planning a path around obstacles and placing blocks "
                + "to cross gaps"},
    };

    /**
     * One MOVE-slot intent from an {@code act_set}/{@code act_plan} {@code move} map:
     * {@code go_to}, {@code walk_straight}, or raw axes. {@code go_to} and {@code walk_straight}
     * together are refused rather than guessed, and the pre-rename {@code route}/{@code to} are
     * refused by name.
     */
    public static ActIntent parseMoveSlot(Map<String, Object> move) {
        refuseRenamedMoveKey(move);
        double[] walk = coordArg(move, "walk_straight", WALK_STRAIGHT_SHAPE);
        double[] goTo = coordArg(move, "go_to", GO_TO_SHAPE);
        if (goTo != null && walk != null) {
            throw new IllegalArgumentException("give either 'walk_straight' (walk in a straight "
                    + "line toward a point, no pathfinding and no building) or 'go_to' (reach a "
                    + "block, planning around obstacles and placing blocks if needed), not both: "
                    + "they are two answers to the same question and the MOVE slot holds one "
                    + "intent. If you have not checked the line, 'go_to' is the one you want");
        }
        if (goTo != null && goTo.length < 3) {
            throw new IllegalArgumentException("'go_to' needs three block coordinates [x,y,z]; a "
                    + "route to a half-specified block is not a request that can be honoured");
        }
        // One read of `sneak` for both destination forms, before either branch. It used to be read
        // only by parseMove (raw axes), so `sneak` was a key the schema advertised as generally
        // available and that meant nothing at all to the two forms an agent actually navigates
        // with. Reading it here is what makes a creep request on `walk_straight` a real request.
        boolean creeping = boolArg(move, "sneak", false);
        if (goTo != null) {
            int budget = intArg(move, "blockBudget", RouteIntent.DEFAULT_BLOCK_BUDGET);
            if (budget < 0) {
                throw new IllegalArgumentException("'blockBudget' must not be negative: " + budget);
            }
            return new RouteIntent((int) Math.floor(goTo[0]),
                    (int) Math.floor(goTo[1]), (int) Math.floor(goTo[2]), budget, creeping);
        }
        if (walk != null) {
            return new NavIntent(walk[0], walk.length > 1 ? walk[1] : 0,
                    walk.length > 2 ? walk[2] : 0, intArg(move, "timeoutTicks", 0), creeping);
        }
        return parseMove(move);
    }

    /**
     * REFUSE a destination key that was renamed, naming the replacement.
     *
     * <p>Presence is the test, not the value: a {@code to}/{@code route} carrying anything at all
     * is a caller reaching for a key that no longer exists, and a map holding that key with a
     * {@code null} is the same mistake with less to go on.
     */
    private static void refuseRenamedMoveKey(Map<String, Object> move) {
        if (move == null) {
            return;
        }
        for (String[] rename : RENAMED_MOVE_KEYS) {
            if (!move.containsKey(rename[0])) {
                continue;
            }
            throw new IllegalArgumentException("act_set move '" + rename[0] + "' has been RENAMED "
                    + "to '" + rename[1] + "' and is no longer accepted -- there is no alias. It "
                    + "still does what it did: '" + rename[1] + "' " + rename[2]
                    + ". Rename the key and resend. This is deliberate: a silent alias is a tool "
                    + "that looks migrated and is not, and the old short name is the one that "
                    + "walked a live player off a 30-block cliff and drowned them");
        }
    }

    /**
     * The RAW-AXES form: direct input, no destination, no routing.
     *
     * <p>Refuses the inert combination — every axis at rest and no duration. Found on a live
     * client: such an intent is accepted, reports {@code phase: ACTIVE} and
     * {@code "moving (tick N), moved 0.00 blocks"} indefinitely, because the applier's terminal
     * branch is guarded by {@code duration > 0} and the only axes are zeros. It never moves the
     * player, never fails, and never terminates — the worst failure this tool surface can have,
     * and strictly worse than a refusal because the caller is told it succeeded.
     *
     * <p>It is also the shape every malformed {@code walk_straight}/{@code go_to} used to fall into
     * before {@link #coordArg} rejected the wrong shape outright, which is how the live client
     * reached it in the first place. Two doors, one fix each.
     */
    public static MoveIntent parseMove(Map<String, Object> m) {
        float forward = floatArg(m, "forward", 0f);
        float strafe = floatArg(m, "strafe", 0f);
        boolean jump = boolArg(m, "jump", false);
        boolean sneak = boolArg(m, "sneak", false);
        boolean sprint = boolArg(m, "sprint", false);
        int duration = intArg(m, "durationTicks", 0);
        if (forward == 0f && strafe == 0f && !jump && !sneak && !sprint && duration <= 0) {
            throw new IllegalArgumentException("act_set move: this asks for no movement at all -- "
                    + "forward, strafe, jump, sneak and sprint are all at rest and durationTicks "
                    + "is 0, so it would report itself as moving forever without moving. "
                    + WALK_STRAIGHT_SHAPE + " Use 'go_to' to reach a block instead of walking a "
                    + "line at it. For a momentary press, give durationTicks.");
        }
        return new MoveIntent(forward, strafe, jump, sneak, sprint, duration);
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
                // A dig has no hit vector: ActActuator.startDig/pumpDig take the block and the face,
                // and where inside the block the ray lands is the game's business (vanilla's
                // clickBlock derives it), so hitX/hitY/hitZ are PLACEMENT arguments -- refused here
                // rather than dropped, which is the difference between "not applicable" and "silently
                // ignored" that a reader could not tell apart before.
                refuseUnusableTarget(m, "dig",
                        "a dig names a block and a face and nothing else",
                        "Omit hitX/hitY/hitZ -- they say where on the face a PLACEMENT clicks, which is "
                                + "'place'",
                        "hitX", "hitY", "hitZ");
                int[] b = requireBlock(m, "dig");
                return InteractIntent.dig(b[0], b[1], b[2], intArg(m, "face", -1));
            }
            case "use":
                // 'use' is the IN-AIR right-click (InteractIntent.useInAir) and has nowhere to put a
                // block target, so one supplied here is REFUSED rather than dropped. Silently ignoring
                // it is the defect this replaces: a caller asking to right-click a specific block got
                // an in-air click and a green result, so nothing in the reply said the block had been
                // discarded. Refusing is also why InteractController.use's intent.hasBlock() branch is
                // unreachable BY PARSER PATH -- no argument here or in a plan step can build a USE
                // with a block; 'place' is the kind that carries the face and the hit offset.
                refuseUnusableTarget(m, "use",
                        "a single in-air right-click has no field for a block target",
                        "Right-click a named block face with 'place' -- it carries the face and the "
                                + "within-block hit offset -- or omit the argument to use the held item "
                                + "in the air",
                        BLOCK_TARGET_KEYS);
                return InteractIntent.useInAir();
            case "place": {
                int[] b = requireBlock(m, "place");
                return InteractIntent.place(b[0], b[1], b[2], intArg(m, "face", -1),
                        floatArg(m, "hitX", 0f), floatArg(m, "hitY", 0f), floatArg(m, "hitZ", 0f));
            }
            case "attack": {
                refuseUnusableTarget(m, "attack",
                        "an attack targets an entity id, not a block",
                        "Use 'dig' or 'place' to act on a block, or omit the argument",
                        BLOCK_TARGET_KEYS);
                int entityId = intArg(m, "entityId", -1);
                if (entityId < 0) {
                    throw new IllegalArgumentException("act_set interact 'attack' needs a non-negative 'entityId'");
                }
                String attackMode = strArg(m, "attack");
                if (attackMode == null) {
                    return InteractIntent.attack(entityId);
                }
                return switch (attackMode.trim().toLowerCase(Locale.ROOT)) {
                    case "plain" -> InteractIntent.attack(entityId);
                    case "crit" -> InteractIntent.critAttack(entityId);
                    default -> throw new IllegalArgumentException(
                            "act_set interact 'attack' attack mode must be 'plain' or 'crit', got '"
                                    + attackMode + "'");
                };
            }
            case "hotbar": {
                refuseUnusableTarget(m, "hotbar",
                        "a hotbar select takes a slot number, not a block",
                        "Use 'dig' or 'place' to act on a block, or omit the argument",
                        BLOCK_TARGET_KEYS);
                int slot = intArg(m, "hotbarSlot", -1);
                if (slot < 0 || slot > 8) {
                    throw new IllegalArgumentException("act_set interact 'hotbar' needs 'hotbarSlot' 0-8");
                }
                return InteractIntent.hotbar(slot);
            }
            case "drop": {
                // Same refusal as 'hotbar' one case up and for the same reason: a drop names a
                // slot in the PLAYER's own bag, so there is no block in the world for it to
                // click. A caller who passed one was pointing at the wrong thing, and honouring
                // the drop while discarding the block would report success for a request whose
                // actual target was never touched.
                refuseUnusableTarget(m, "drop",
                        "a drop empties a slot in the player's own inventory, not a block",
                        "Name the slot with 'slot' (0-35, vanilla's mainInventory order, so 0-8 is"
                                + " the hotbar). To act on a block, use 'dig' or 'place'",
                        BLOCK_TARGET_KEYS);
                int slot = intArg(m, "slot", -1);
                if (slot < 0 || slot >= DropController.SLOT_COUNT) {
                    throw new IllegalArgumentException("act_set interact 'drop' needs 'slot' 0-"
                            + (DropController.SLOT_COUNT - 1)
                            + " (the player's own 36 inventory slots; use kind='hotbar' to choose which"
                            + " one is in hand), got " + slot);
                }
                return InteractIntent.dropStack(slot);
            }
            case "hold": {
                // Same shape as 'use' one case up: HOLD drives the held item's own use (vanilla's use
                // key, the target resolved by the server from what the player is looking at), so a
                // block target has nowhere to go and is refused rather than dropped -- a caller who
                // asked to hold a use against a named block was told it worked while something else
                // happened.
                refuseUnusableTarget(m, "hold",
                        "the sustained use keeps vanilla's use key asserted and the target is whatever "
                                + "the crosshair is on, so there is no block to name",
                        "Right-click a named block face with 'place', or omit the argument to hold the "
                                + "use in the air",
                        BLOCK_TARGET_KEYS);
                int holdTicks = intArg(m, "holdTicks", 0);
                if (holdTicks < 0) {
                    throw new IllegalArgumentException(
                            "act_set interact 'hold' needs 'holdTicks' >= 0, got " + holdTicks);
                }
                return holdTicks > 0
                        ? InteractIntent.holdThenRelease(holdTicks)
                        : InteractIntent.holdUntilDone();
            }
            case "block": {
                // No tick count and no holdTicks, on purpose: the whole reason this kind exists is
                // that the tick count is the one thing the caller cannot supply. An argument here
                // would be silently dropped, which is the failure mode refuseUnusableTarget exists
                // to prevent.
                refuseUnusableTarget(m, "block",
                        "blocking lasts until you release it, so it takes no block and no tick count",
                        "End it with act_set interact kind=release, or act_cancel",
                        "block", "face", "hitX", "hitY", "hitZ", "holdTicks", "hotbarSlot", "attack");
                return InteractIntent.block();
            }
            case "release": {
                refuseUnusableTarget(m, "release",
                        "a release is a release of the hand; it takes no block, entity or tick count",
                        "It only means anything while a hold or a block is running -- read act_status "
                                + "to see whether one is",
                        "block", "face", "hitX", "hitY", "hitZ", "holdTicks", "entityId",
                        "hotbarSlot", "attack");
                return InteractIntent.releaseUse();
            }
            default:
                throw new IllegalArgumentException(
                        "act_set interact 'kind' must be one of "
                                + "dig|use|place|attack|hotbar|drop|hold|block|release, "
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

    /** The block-target arguments: which block, which face of it, and where on that face. */
    private static final String[] BLOCK_TARGET_KEYS = {"block", "face", "hitX", "hitY", "hitZ"};

    /**
     * Refuse block-target arguments that {@code kind} has nowhere to put, naming every one that was
     * SUPPLIED instead of dropping it.
     *
     * <p>A VALUE is what is tested, not a key and not parseability: the offence is that the
     * caller supplied a block the {@code kind} cannot use. A key written with a null value is not
     * supplied anything -- that is how a model fills an optional field it has nothing for, and
     * {@code IoProbe} itself calls a null "missing". A malformed value ([x,y]) is still refused
     * here rather than falling through {@link #intTriple}'s null, because that one IS a caller
     * error.
     *
     * <p>Silence is what this replaces, and silence is the worse of the two answers: the caller asked
     * for an action on a named block, the tool performed a different action, and the reply said it
     * succeeded -- so neither the reply nor {@code act_status}, which reports the action that really
     * ran, can be read as a complaint about the argument that was ignored.
     */
    private static void refuseUnusableTarget(Map<String, Object> m, String kind, String rule,
                                             String remedy, String... keys) {
        List<String> supplied = new ArrayList<>(keys.length);
        for (String key : keys) {
            // A VALUE, not a key. The write of the key is not the offence -- writing a null IS
            // how a model fills in an optional field it has nothing for, IoProbe's own comment
            // calls a null "missing", and every other optional argument in this parser is read
            // through intArg/floatArg/intTriple, all of which treat null as absent and fall
            // back. Testing containsKey made this the one reader in the file that disagreed
            // with the rest of it, and turned a previously legal call into a refusal:
            // {"kind":"hotbar","hotbarSlot":3,"block":null} picked slot 3 before this guard
            // and now throws. What is still refused is a block that was actually WRITTEN --
            // [x,y] included, because a malformed value is a caller's error and silently
            // dropping it is the failure this method exists to stop.
            if (m.get(key) != null) {
                supplied.add("'" + key + "'");
            }
        }
        if (supplied.isEmpty()) {
            return;
        }
        throw new IllegalArgumentException("act_set interact '" + kind + "': " + rule + " -- "
                + supplied + " cannot be honoured. " + remedy);
    }
}
