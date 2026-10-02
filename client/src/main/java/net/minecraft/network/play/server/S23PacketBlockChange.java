package net.minecraft.network.play.server;

import java.io.IOException;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.UnknownBlockStates;

public class S23PacketBlockChange implements Packet<INetHandlerPlayClient>
{
    private BlockPos blockPosition;
    private IBlockState blockState;

    public S23PacketBlockChange()
    {
    }

    public S23PacketBlockChange(World worldIn, BlockPos blockPositionIn)
    {
        this.blockPosition = blockPositionIn;
        this.blockState = worldIn.getBlockState(blockPositionIn);
    }

    /**
     * Reads the raw packet data from the data stream.
     */
    public void readPacketData(PacketBuffer buf) throws IOException
    {
        this.blockPosition = buf.readBlockPos();

        int stateId = buf.readVarIntFromBuffer();
        IBlockState state = (IBlockState)Block.BLOCK_STATE_IDS.getByValue(stateId);

        if (state == null)
        {
            // The null is UNCHANGED and stays: turning it into air here would be a wire-behaviour
            // decision, and the air answer this client already commits to (ExtendedBlockStorage
            // get) exists for a stated compatibility reason -- a 1.8.9 client on a newer server
            // legitimately receives ids it has never heard of, and refusing them would break the
            // connection. Inventing the same answer a second time, one layer up, without that
            // argument having been made, is exactly the substitution this slice was told not to
            // do. So the null is kept and the MISS is made countable instead: see
            // UnknownBlockStates for what is counted, and for why a null arrival is counted apart
            // from an air fallback -- the two are different failures and a reader of the F3 line
            // has to be able to tell them apart.
            UnknownBlockStates.recordNullArrival(stateId);
        }

        this.blockState = state;
    }

    /**
     * Writes the raw packet data to the data stream.
     */
    public void writePacketData(PacketBuffer buf) throws IOException
    {
        buf.writeBlockPos(this.blockPosition);
        buf.writeVarIntToBuffer(Block.BLOCK_STATE_IDS.get(this.blockState));
    }

    /**
     * Passes this Packet on to the NetHandler for processing.
     */
    public void processPacket(INetHandlerPlayClient handler)
    {
        handler.handleBlockChange(this);
    }

    public IBlockState getBlockState()
    {
        return this.blockState;
    }

    public BlockPos getBlockPosition()
    {
        return this.blockPosition;
    }
}
