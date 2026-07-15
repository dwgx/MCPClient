package net.marcloud.mcp.core.drivers.world;

import java.util.List;

/**
 * PHASE W.5 — per-slot inventory using the item REGISTRY name (not displayName),
 * addressable by slot index. {@code maxDamage} null for non-damageable items.
 */
public record InventoryView(int selectedSlot, List<Slot> slots) {

    public record Slot(int index, String item, int count, int damage, Integer maxDamage) {
    }
}
