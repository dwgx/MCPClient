package net.marcloud.mcp.core.eval;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.MathHelper;

import net.marcloud.mcp.core.GameAccess;

/**
 * The one {@link NightShelter.Body} a running client can supply: the local player, in the world
 * the client is currently in.
 *
 * <p><b>Why this class exists rather than an anonymous {@code Body} in {@code McpCore}.</b> The
 * wiring has to hand {@link NightShelter} something that reaches the live game, and the three
 * facts that decide how expensive that is are all here:
 *
 * <ul>
 *   <li><b>It allocates nothing per tick, by construction.</b> {@link WorldBlockView} and this
 *       {@code Body} are both built once, at wiring time, and the only thing that changes per
 *       tick is the reference the view is pointed at. {@link #grid()} writes
 *       {@code view.world(w)} on every tick and the {@code volatile} store is the entire cost;
 *       before this class existed the alternative was one {@code WorldBlockView} plus one
 *       anonymous {@code Body} per tick, on the game's hot path, to carry the same two
 *       references. {@code EventBus} carries a dispatch cache for exactly that reason and says so
 *       on its own class ({@code EventBus.java:20-26}).</li>
 *   <li><b>The world reference is read exactly once per grid access.</b> {@link NightShelter#onTick}
 *       calls {@link #world()} to decide whether there is anything to measure and then
 *       {@link #grid()}; both read {@code game.world()}, which is a field read off the
 *       {@code Minecraft} singleton and never dereferences a collaborator that may be absent.</li>
 *   <li><b>"No body" and "no world" are the same answer.</b> {@code player()} is null outside a
 *       world and so is {@code world()}, so the feet cells report 0 and the view reports null —
 *       both of which {@link Enclosure} already reads as "not enclosed", the safe direction.</li>
 * </ul>
 *
 * <p><b>Threading.</b> GAME THREAD ONLY, inherited from {@link WorldBlockView} and from
 * {@link NightShelter}: every method here reads live game state, and the only caller is the tick
 * handler.
 */
public final class ClientBody implements NightShelter.Body {

    private final GameAccess game;

    /** Built once. Re-pointed, never replaced — see the class javadoc. */
    private final WorldBlockView view;

    public ClientBody(GameAccess game) {
        this(game, new WorldBlockView(null));
    }

    /**
     * @param view the grid this body hands {@link NightShelter}; built once and re-pointed, so a
     *             caller with a pre-built grid can supply it rather than have a second one made
     */
    public ClientBody(GameAccess game, WorldBlockView view) {
        this.game = game;
        this.view = view;
    }

    @Override
    public Object world() {
        return game.world();
    }

    @Override
    public long worldTime() {
        WorldClient w = game.world();
        return w == null ? 0L : w.getWorldTime();
    }

    /**
     * The same {@link WorldBlockView} every tick, pointed at the world this tick is in.
     *
     * <p>Re-pointing rather than building is the whole point of this class, so the write happens
     * unconditionally: an identity check would save one volatile store and cost a branch that
     * has to be right about a world changing under it.
     */
    @Override
    public Enclosure.CellGrid grid() {
        view.world(game.world());
        return view;
    }

    @Override
    public int feetX() {
        EntityPlayerSP p = game.player();
        return p == null ? 0 : MathHelper.floor_double(p.posX);
    }

    @Override
    public int feetY() {
        EntityPlayerSP p = game.player();
        return p == null ? 0 : MathHelper.floor_double(p.posY);
    }

    @Override
    public int feetZ() {
        EntityPlayerSP p = game.player();
        return p == null ? 0 : MathHelper.floor_double(p.posZ);
    }
}
