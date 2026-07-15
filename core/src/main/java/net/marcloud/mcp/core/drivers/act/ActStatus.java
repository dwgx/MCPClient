package net.marcloud.mcp.core.drivers.act;

import java.util.List;

/**
 * An immutable snapshot of the whole act runtime for the {@code act_status} tool:
 * the current clock tick plus one line per slot. Built off a consistent read of
 * each slot's {@link SlotRecord} so the AI can see "what am I doing right now and
 * did the last thing finish".
 *
 * @param tickNow the clock tick at snapshot time
 * @param slots   one entry per {@link ActSlot}, in enum order
 */
public record ActStatus(long tickNow, List<SlotStatus> slots) {

    /**
     * One slot's public status.
     *
     * @param slot        which slot
     * @param phase       lifecycle phase
     * @param hasIntent   whether an intent currently occupies the slot
     * @param intentKind  a short label for the intent ("MOVE"/"LOOK:SET"/"INTERACT:DIG"/"-")
     * @param ticksActive how many ticks it has been ACTIVE
     * @param message     last human-readable status line
     */
    public record SlotStatus(
            ActSlot slot,
            ActPhase phase,
            boolean hasIntent,
            String intentKind,
            int ticksActive,
            String message) {
    }
}
