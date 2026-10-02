package net.marcloud.mcp.core.drivers.world;

import java.util.Set;

/**
 * Whether an entity can be hit, and whether hitting it is a good idea.
 *
 * <p>Both answers already exist in the vendored 1.8.9 source and neither reached the tool surface.
 * The entity listing carried id, type, position, distance, hp and a name, which is enough to walk
 * toward something and not enough to decide what to do when you arrive.
 *
 * <h2>Reach</h2>
 *
 * <p>{@code NetHandlerPlayServer.processUseEntity} ({@code :899-935}) compares
 * {@code getDistanceSqToEntity(entity) < d0}, where {@code d0} is {@code 36.0} when
 * {@code canEntityBeSeen} and {@code 9.0} when not. Squared, so radii of 6.0 and 3.0. The second
 * number is the one that catches people out: a target four blocks away is accepted when you can see
 * it and silently refused when you cannot, with no error in either direction.
 *
 * <p>Separately, {@code EntityRenderer.getMouseOver} ({@code :498-502}) nulls a pointed entity
 * whose eye-to-hit distance exceeds {@code 3.0} outside creative. So hovering and hitting have
 * different reaches, and an entity can be attackable by id while being un-aimable by crosshair --
 * which is why these are two methods and not one.
 *
 * <h2>Permission</h2>
 *
 * <p>Attacking an {@code EntityItem}, {@code EntityXPOrb} or {@code EntityArrow}, or oneself,
 * makes the server {@code kickPlayerFromServer}. That is a disconnect, not a refused click, and an
 * agent that learns it by doing it has lost the session.
 *
 * <h2>Hostility</h2>
 *
 * <p>By game class, not by health or by proximity. "Should I swing" turns on whether the thing
 * attacks players, which the vanilla hierarchy answers and a health threshold does not.
 */
public final class EntityCombat {

    /**
     * The squared reach the server applies to an INTERACT, INTERACT_AT or ATTACK packet.
     *
     * <p>Strictly less than, matching the source: at exactly 36.0 the server refuses, so an
     * inclusive comparison here would report a boundary target as in reach and have the click
     * silently do nothing -- the same off-by-a-boundary the block-reach check pins from its side.
     */
    public static boolean serverAccepts(double distSq, boolean canSee) {
        return distSq < (canSee ? 36.0D : 9.0D);
    }

    /** The radius the server applies, for a message. */
    public static double reachRadius(boolean canSee) {
        return canSee ? 6.0D : 3.0D;
    }

    /**
     * Whether the crosshair can land on the entity: {@code EntityRenderer.getMouseOver}'s 3.0
     * clamp, which is eye-to-hit distance and applies outside creative only.
     */
    public static boolean pickable(double eyeToHit) {
        return eyeToHit <= 3.0D;
    }

    /**
     * Entities the server KICKS the player for attacking.
     *
     * <p>Verbatim from {@code processUseEntity}: {@code EntityItem}, {@code EntityXPOrb},
     * {@code EntityArrow}, or the player. A minecart is not on that list and is therefore NOT here:
     * adding it would refuse a legal action on the strength of a guess, which is the failure this
     * class exists to remove.
     */
    private static final Set<String> UNATTACKABLE = Set.of("Item", "XPOrb", "Arrow");

    /** Vanilla's simple-name form of {@code EntityPlayer}, i.e. "EntityPlayer" and "EntityPlayerMP". */
    private static final Set<String> SELF = Set.of("EntityPlayer", "EntityPlayerMP");

    /**
     * Whether attacking {@code type} is legal at all.
     *
     * <p>A DENY list, verbatim from vanilla, and deliberately not an allow list. An allow list
     * would have to enumerate every mob in the game -- horses, wolves, iron golems, anything a
     * mod adds -- and would refuse a legal attack on all of them. Vanilla kicks exactly four
     * things; refusing more than vanilla refuses would make this surface wrong in the other
     * direction, and an agent that stops attacking cows is as broken as one that disconnects.
     *
     * <p>So an unrecognised name is PERMITTED, because vanilla permits it. What this cannot do is
     * catch a mod whose entity the server also kicks; that list is not readable from the client.
     */
    public static boolean attackable(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        return !UNATTACKABLE.contains(type) && !SELF.contains(type);
    }

    /**
     * Mobs that attack players, by vanilla's own hierarchy.
     *
     * <p>Stated as a name set rather than derived from {@code EntityMonster}, because the capture
     * runs against whatever entity is present and a subclass lookup is one more thing that can
     * disagree with the game. Names are the surface the caller already sees.
     */
    private static final Set<String> HOSTILE = Set.of(
            "Zombie", "Skeleton", "Creeper", "Spider", "CaveSpider", "Enderman", "Slime",
            "Witch", "PigZombie", "Blaze", "Ghast", "MagmaCube", "Silverfish", "Endermite");

    public static boolean hostile(String type) {
        return type != null && HOSTILE.contains(type);
    }

    private EntityCombat() {
    }
}
