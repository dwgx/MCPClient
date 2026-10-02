package net.minecraft.network.play.server;

import java.io.IOException;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.util.BlockPos;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.UnknownBlockStates;

public class S22PacketMultiBlockChange implements Packet<INetHandlerPlayClient>
{
    private ChunkCoordIntPair chunkPosCoord;
    private S22PacketMultiBlockChange.BlockUpdateData[] changedBlocks;

    public S22PacketMultiBlockChange()
    {
    }

    public S22PacketMultiBlockChange(int p_i45181_1_, short[] crammedPositionsIn, Chunk chunkIn)
    {
        this.chunkPosCoord = new ChunkCoordIntPair(chunkIn.xPosition, chunkIn.zPosition);
        this.changedBlocks = new S22PacketMultiBlockChange.BlockUpdateData[p_i45181_1_];

        for (int i = 0; i < this.changedBlocks.length; ++i)
        {
            this.changedBlocks[i] = new S22PacketMultiBlockChange.BlockUpdateData(crammedPositionsIn[i], chunkIn);
        }
    }

    /**
     * Reads the raw packet data from the data stream.
     */
    public void readPacketData(PacketBuffer buf) throws IOException
    {
        this.chunkPosCoord = new ChunkCoordIntPair(buf.readInt(), buf.readInt());
        this.changedBlocks = new S22PacketMultiBlockChange.BlockUpdateData[buf.readVarIntFromBuffer()];

        for (int i = 0; i < this.changedBlocks.length; ++i)
        {
            short pos = buf.readShort();
            int stateId = buf.readVarIntFromBuffer();
            IBlockState state = (IBlockState)Block.BLOCK_STATE_IDS.getByValue(stateId);

            if (state == null)
            {
                // The null is UNCHANGED here for the same reason as in S23PacketBlockChange, and
                // the reasoning is not repeated because repeating it is how it goes stale: this
                // is a wire-behaviour decision, not a visibility one, and a slice whose job is to
                // make a silence countable does not get to also choose the answer the silence was
                // hiding. So the null is kept and the MISS is counted -- once per decode, at the
                // site that produces it, before any caller can abort the loop over the remaining
                // entries. See UnknownBlockStates for the count and for why it is separate from
                // the air-fallback count.
                UnknownBlockStates.recordNullArrival(stateId);
            }

            this.changedBlocks[i] = new S22PacketMultiBlockChange.BlockUpdateData(pos, state);
        }
    }

    /**
     * Writes the raw packet data to the data stream.
     */
    public void writePacketData(PacketBuffer buf) throws IOException
    {
        buf.writeInt(this.chunkPosCoord.chunkXPos);
        buf.writeInt(this.chunkPosCoord.chunkZPos);
        buf.writeVarIntToBuffer(this.changedBlocks.length);

        for (S22PacketMultiBlockChange.BlockUpdateData s22packetmultiblockchange$blockupdatedata : this.changedBlocks)
        {
            buf.writeShort(s22packetmultiblockchange$blockupdatedata.func_180089_b());
            buf.writeVarIntToBuffer(Block.BLOCK_STATE_IDS.get(s22packetmultiblockchange$blockupdatedata.getBlockState()));
        }
    }

    /**
     * Passes this Packet on to the NetHandler for processing.
     */
    public void processPacket(INetHandlerPlayClient handler)
    {
        handler.handleMultiBlockChange(this);
    }

    public S22PacketMultiBlockChange.BlockUpdateData[] getChangedBlocks()
    {
        return this.changedBlocks;
    }

    public class BlockUpdateData
    {
        private final short chunkPosCrammed;
        private final IBlockState blockState;

        public BlockUpdateData(short p_i45984_2_, IBlockState state)
        {
            this.chunkPosCrammed = p_i45984_2_;
            this.blockState = state;
        }

        public BlockUpdateData(short p_i45985_2_, Chunk chunkIn)
        {
            this.chunkPosCrammed = p_i45985_2_;
            this.blockState = chunkIn.getBlockState(this.getPos());
        }

        public BlockPos getPos()
        {
            return new BlockPos(S22PacketMultiBlockChange.this.chunkPosCoord.getBlock(this.chunkPosCrammed >> 12 & 15, this.chunkPosCrammed & 255, this.chunkPosCrammed >> 8 & 15));
        }

        public short func_180089_b()
        {
            return this.chunkPosCrammed;
        }

        public IBlockState getBlockState()
        {
            return this.blockState;
        }
    }
}
