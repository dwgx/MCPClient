package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import net.minecraft.util.MovementInput;

/**
 * Keeps an {@link ActMovementInput} installed in the player's {@code movementInput}
 * slot so the MOVE intent can drive the player. Subscribes to {@link TickEvent}
 * (game thread) and, while armed, ensures every live player has our wrapper in
 * place — re-swapping whenever the player IDENTITY changes, because world-join,
 * respawn, and dimension change re-instantiate {@code EntityPlayerSP} (and thus a
 * fresh vanilla {@code movementInput}), silently dropping a previous override.
 *
 * <p>All game touches go through a {@link PlayerInputSlot}, so the swap/restore and
 * identity-change logic is exercised headlessly with a fake; the live wiring uses
 * {@link GameAccessInputSlot}. There is no Byte Buddy here — the public field plus
 * an {@link ActMovementInput} subclass is the whole mechanism.
 *
 * <p>{@link #disarm()} restores the exact vanilla input we wrapped, so the tool
 * lifecycle is genuinely reversible: after disarm the player moves purely from
 * keyboard/mouse again.
 */
public final class MovementInputInstaller {

    private final PlayerInputSlot slot;
    private final MoveIntentView view;

    private final java.util.function.Consumer<TickEvent> handler = this::onTick;

    private volatile EventBus bus;
    private volatile boolean armed;

    /** The player identity we last installed our wrapper on (for change detection). */
    private Object installedOn;
    /** The wrapper currently installed (so we can confirm/restore). */
    private ActMovementInput installed;

    public MovementInputInstaller(PlayerInputSlot slot, MoveIntentView view) {
        this.slot = slot;
        this.view = view;
    }

    /** Subscribe to ticks. Call {@link #arm()} to actually start swapping. */
    public void attach(EventBus bus) {
        this.bus = bus;
        bus.subscribe(TickEvent.class, handler);
    }

    /** Stop listening entirely (also restores vanilla if still armed). */
    public void detach() {
        disarm();
        EventBus b = this.bus;
        if (b != null) {
            b.unsubscribe(handler);
        }
    }

    /** Begin ensuring our wrapper is installed each tick. */
    public void arm() {
        this.armed = true;
    }

    /** True if arming is active. */
    public boolean isArmed() {
        return armed;
    }

    /**
     * Stop overriding and restore the vanilla input we wrapped. Idempotent. After
     * this the player's {@code movementInput} is exactly what it was before we
     * swapped it (its {@link ActMovementInput#original() original}).
     */
    public synchronized void disarm() {
        armed = false;
        if (installed != null) {
            // Only restore if OUR wrapper is still the live one (else the game
            // replaced the player under us and there is nothing of ours to undo).
            MovementInput live = slot.get();
            if (live == installed) {
                slot.set(installed.original());
            }
        }
        installed = null;
        installedOn = null;
    }

    /**
     * Per-tick maintenance on the game thread. Public and directly driveable with a
     * synthetic {@link TickEvent} so the installer is testable without a live bus.
     */
    public synchronized void onTick(TickEvent event) {
        if (!armed) {
            return;
        }
        Object player = slot.playerIdentity();
        if (player == null) {
            // Left the world: forget our install so re-join gets a fresh swap.
            installed = null;
            installedOn = null;
            return;
        }
        MovementInput live = slot.get();
        boolean identityChanged = player != installedOn;
        boolean ourWrapperGone = !(live instanceof ActMovementInput);

        if (identityChanged || ourWrapperGone) {
            // Wrap whatever vanilla input the (possibly new) player currently has.
            MovementInput toWrap = live instanceof ActMovementInput ami ? ami.original() : live;
            ActMovementInput wrapper = new ActMovementInput(toWrap, view);
            slot.set(wrapper);
            installed = wrapper;
            installedOn = player;
        }

        // Apply sprint intent (not a MovementInput field).
        ActMovementInput cur = installed;
        if (cur != null) {
            slot.setSprinting(cur.sprintRequested());
        }
    }
}
