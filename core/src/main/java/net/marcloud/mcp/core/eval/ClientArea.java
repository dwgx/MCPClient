package net.marcloud.mcp.core.eval;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.MathHelper;

import net.marcloud.mcp.core.GameAccess;

/**
 * The one {@link DawnChestRegion.Area} a running client can supply: a cube of cells around the
 * local player, read through the same {@link WorldBlockView} the shelter counter reads.
 *
 * <p><b>Why this class exists rather than an anonymous {@code Area} in {@code McpCore}.</b> The
 * wiring has to hand {@link DawnChestRegion} something that reaches the live game, and two of the
 * facts that decide how expensive that is only fit in a named class: the view is built ONCE and
 * re-pointed rather than allocated per tick, and the world reference is read once per access
 * instead of once per cell.
 *
 * <p><b>It allocates nothing per tick, by construction.</b> {@link WorldBlockView} and this
 * {@code Area} are both built once, at wiring time, and the only thing that changes per tick is
 * the reference the view is pointed at -- {@link #grid()} writes {@code view.world(w)} and the
 * {@code volatile} store is the entire cost. {@code EventBus} carries a dispatch cache for exactly
 * that reason and says so on its own class ({@code EventBus.java:20-26}).
 *
 * <p><b>"No player" and "no world" are the same answer.</b> {@code player()} is null outside a
 * world and so is {@code world()}, so the feet cells report 0 and the view reports null -- and
 * {@link DawnChestRegion} already reads a null world as "nothing to measure", so the tick handler
 * needs no separate branch and no separate state.
 *
 * <p><b>The clock is the day-cycle clock, not the total-time clock.</b> {@link #worldTime()} asks
 * {@code World.getWorldTime()}, the same accessor {@link ClientBody} uses, because
 * {@link DawnShelter}'s window boundaries and {@link NightHealth}'s come from that curve and a
 * third clock would make the three rulers describe three different nights.
 *
 * <p><b>Threading.</b> GAME THREAD ONLY, inherited from {@link WorldBlockView} and from
 * {@link DawnChestRegion}: every method reads live chunk state, and the only caller is the tick
 * handler.
 */
public final class ClientArea implements DawnChestRegion.Area {

    private final GameAccess game;

    /** Built once. Re-pointed, never replaced — see the class javadoc. */
    private final WorldBlockView view;

    public ClientArea(GameAccess game) {
        this(game, new WorldBlockView(null));
    }

    /**
     * @param view the grid this area hands {@link DawnChestRegion}; built once and re-pointed, so a
     *             caller with a pre-built grid can supply it rather than have a second one made
     */
    public ClientArea(GameAccess game, WorldBlockView view) {
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
     * unconditionally: an identity check would save one volatile store and cost a branch that has
     * to be right about a world changing under it.
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