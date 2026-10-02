package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import io.netty.buffer.Unpooled;

import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Wave 13 vendored audit: the network half of the unknown-item-id contract.
 *
 * <p><b>The defect this pins.</b> {@code ItemStack} already refuses an item it cannot resolve.
 * {@code ItemStack.loadItemStackFromNBT} reads the tag and then does exactly that:
 * {@code return itemstack.getItem() != null ? itemstack : null;} (ItemStack.java:107). The sibling
 * path did not. {@code PacketBuffer.readItemStackFromBuffer} called
 * {@code new ItemStack(Item.getItemById(i), j, k)} with no check, and
 * {@code Item.getItemById} is {@code (Item)itemRegistry.getObjectById(id)} (Item.java:87), which is
 * null for any id this client does not register. So a slot arrived holding an ItemStack whose
 * {@code item} was null.
 *
 * <p><b>Why that is a defect and not a curiosity.</b> The stack is not inert. The very next thing
 * any container or inventory does with it dereferences the null: {@code getMaxStackSize()} is
 * {@code return this.getItem().getItemStackLimit();} (ItemStack.java:234), reached from
 * {@code Container.mergeItemStack}'s {@code stack.isStackable()} (Container.java:607). Note the
 * same class guards this exact condition a few lines away --
 * {@code isItemStackDamageable()} is {@code this.item == null ? false : ...} (ItemStack.java:250) --
 * so the class demonstrably admits its item can be null while the accessors around it do not
 * agree.
 *
 * <p><b>And the throw does not stay local.</b> A packet arriving off the netty thread is not run
 * inline: {@code PacketThreadUtil.checkThreadAndEnqueue} schedules it and throws
 * {@code ThreadQuickExitException} (PacketThreadUtil.java:11-13). The queue is drained by
 * {@code Util.runTask((FutureTask)this.scheduledTasks.poll(), logger)} (Minecraft.java:1105), and
 * {@code Util.runTask} catches the failure, logs it, and returns null (Util.java:21-33) -- the task
 * has already been polled off the queue. So one unreadable slot costs the rest of that tick's
 * queued packets. That is the "silently drops a packet" shape, and it is invisible to a client
 * that is otherwise healthy.
 *
 * <p><b>Why the fix reads the tag before it decides.</b> Returning early on the null id would
 * leave the tag on the wire, and every slot after it in the same packet would then decode shifted
 * by the tag's length. The guard therefore reads and discards the tag exactly as the known-id
 * branch does, so a multi-slot packet keeps its slots aligned. This test pins that: the second
 * slot of a two-slot payload must still decode as itself.
 *
 * <p><b>Both directions are asserted.</b> A test that only proves the guard fires passes against a
 * guard that refuses every id, which would be a far worse defect than the one it replaced. So
 * {@link #aRegisteredIdStillDecodesToAUsableStack} pins that the guard is narrow.
 */
public class VendoredUnknownItemIdIsRefusedTest {

    /**
     * Item id 260 is {@code apple}: {@code registerItem(260, "apple", ...)} at Item.java:769.
     * Chosen from the source rather than from a constant so the test fails loudly if the vendored
     * registry ever renumbers.
     */
    private static final int REGISTERED_APPLE_ID = 260;

    /**
     * Item id 426 is a hole in the vendored registry: 256..425 and 427..431 are registered
     * contiguously and 2256..2267 follow, so 426 is registered by nothing. Item.java:935-936 is
     * where the gap is visible -- {@code registerItem(425, "banner", ...)} is immediately followed
     * by {@code registerItem(427, "spruce_door", ...)}.
     */
    private static final int UNREGISTERED_ID = 426;

    @BeforeClass
    public static void bootstrapRegistries() {
        // Item's static initialiser registers the ItemBlocks through Blocks' fields, and Blocks
        // refuses to initialise before Bootstrap has run (Blocks.java:250-253), so the order is
        // forced rather than incidental.
        Bootstrap.register();
        assertTrue("bootstrap should have registered", Bootstrap.isRegistered());
    }

    /** Writes one slot in the 1.8 wire shape: id(short) size(byte) damage(short) tag(nbt). */
    private static void writeSlot(PacketBuffer buf, int itemId, int size, int damage) {
        buf.writeShort(itemId);
        buf.writeByte(size);
        buf.writeShort(damage);
        buf.writeNBTTagCompoundToBuffer(null);
    }

    @Test
    public void aRegisteredIdStillDecodesToAUsableStack() throws IOException {
        // The reverse direction: the guard must not be a blanket refusal. An unregistered stack is
        // a lost slot; a registered one refused is every slot in the game.
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        writeSlot(buf, REGISTERED_APPLE_ID, 3, 0);

        ItemStack stack = buf.readItemStackFromBuffer();

        assertNotNull("a registered id must still decode", stack);
        assertEquals("count survives", 3, stack.stackSize);
        assertEquals("damage survives", 0, stack.getItemDamage());
        assertNotNull("the item resolves", stack.getItem());
        assertEquals("the id on the wire is the id in the registry",
                REGISTERED_APPLE_ID, Item.getIdFromItem(stack.getItem()));
        assertTrue("and the stack is usable rather than merely non-null",
                stack.getMaxStackSize() > 0);
        assertEquals("the whole slot was consumed", 0, buf.readableBytes());
    }

    @Test
    public void anUnregisteredIdBecomesAnEmptySlotRatherThanANullItemStack() throws IOException {
        assertNull("the id under test must really be unregistered, or this test proves nothing",
                Item.getItemById(UNREGISTERED_ID));

        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        writeSlot(buf, UNREGISTERED_ID, 64, 0);

        // Before the fix this returned a non-null ItemStack with a null item.
        assertNull("an unregistered id must read as an empty slot", buf.readItemStackFromBuffer());
        assertEquals("and it must consume exactly its own bytes", 0, buf.readableBytes());
    }

    @Test
    public void aRefusedSlotDoesNotShiftTheSlotsAfterIt() throws IOException {
        // The reason the tag is read before the decision is made. If the guard returned early,
        // this second slot would be decoded out of the first slot's leftover bytes.
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        writeSlot(buf, UNREGISTERED_ID, 64, 0);
        writeSlot(buf, REGISTERED_APPLE_ID, 7, 1);

        assertNull("first slot is refused", buf.readItemStackFromBuffer());

        ItemStack second = buf.readItemStackFromBuffer();
        assertNotNull("the slot after a refused one still decodes", second);
        assertEquals("with its own count", 7, second.stackSize);
        assertEquals("and its own damage", 1, second.getItemDamage());
        assertNotNull("and its own item", second.getItem());
        assertEquals("and the packet is fully consumed", 0, buf.readableBytes());
    }

    @Test
    public void theNetworkPathAndTheNbtPathAgreeOnTheContract() throws IOException {
        // The defect was an internal inconsistency: one path in the same class refused an
        // unresolved item and its sibling did not. Pin the agreement rather than the shape.
        ItemStack fromNbt = ItemStack.loadItemStackFromNBT(
                new ItemStack(Item.getItemById(UNREGISTERED_ID), 64, 0).writeToNBT(
                        new net.minecraft.nbt.NBTTagCompound()));
        assertNull("the NBT path's contract, for comparison", fromNbt);
    }
}