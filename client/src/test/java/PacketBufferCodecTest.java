import static org.junit.Assert.assertEquals;

import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.BlockPos;
import org.junit.Test;

/**
 * Golden wire-format tests for PacketBuffer primitives.
 *
 * These lock the 1.8.9 protocol encoding byte-for-byte so that dependency
 * upgrades (Guava, Netty, ...) cannot silently alter what goes on the wire.
 * If any of these fail, the client is no longer protocol-compatible with a
 * vanilla 1.8.9 server. Pure unit tests — no game boot needed.
 */
public class PacketBufferCodecTest {

    private static PacketBuffer buf() {
        return new PacketBuffer(Unpooled.buffer());
    }

    private static String hex(PacketBuffer b) {
        StringBuilder sb = new StringBuilder();
        b.markReaderIndex();
        while (b.readableBytes() > 0) {
            sb.append(String.format("%02X", b.readUnsignedByte()));
        }
        b.resetReaderIndex();
        return sb.toString();
    }

    @Test
    public void varIntGoldenBytes() {
        // Canonical VarInt encodings from the Minecraft protocol spec.
        assertVarInt(0, "00");
        assertVarInt(1, "01");
        assertVarInt(127, "7F");
        assertVarInt(128, "8001");
        assertVarInt(255, "FF01");
        assertVarInt(2097151, "FFFF7F");
        assertVarInt(2147483647, "FFFFFFFF07");
        assertVarInt(-1, "FFFFFFFF0F");
        assertVarInt(-2147483648, "8080808008");
    }

    private void assertVarInt(int value, String expectedHex) {
        PacketBuffer b = buf();
        b.writeVarIntToBuffer(value);
        assertEquals("VarInt " + value + " encoding", expectedHex, hex(b));
        assertEquals("VarInt " + value + " round-trip", value, b.readVarIntFromBuffer());
    }

    @Test
    public void varLongRoundTrip() {
        long[] vals = {0L, 1L, 127L, 128L, 2147483648L, -1L, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long v : vals) {
            PacketBuffer b = buf();
            b.writeVarLong(v);
            assertEquals("VarLong round-trip " + v, v, b.readVarLong());
        }
    }

    @Test
    public void stringUtf8RoundTrip() {
        // ASCII, multi-byte UTF-8, and CJK must survive intact.
        String[] vals = {"", "hello", "MC|Brand", "café", "你好世界", "😀"};
        for (String s : vals) {
            PacketBuffer b = buf();
            b.writeString(s);
            assertEquals("String round-trip [" + s + "]", s, b.readStringFromBuffer(32767));
        }
    }

    @Test
    public void stringLengthPrefixIsVarIntByteCount() {
        PacketBuffer b = buf();
        b.writeString("AB");
        // "AB" = 2 UTF-8 bytes -> VarInt 0x02, then 'A'(41) 'B'(42)
        assertEquals("024142", hex(b));
    }

    @Test
    public void uuidRoundTripAndWidth() {
        UUID u = UUID.fromString("12345678-90ab-cdef-1234-567890abcdef");
        PacketBuffer b = buf();
        b.writeUuid(u);
        assertEquals("UUID is 16 bytes (two longs, MSB first)", 16, b.readableBytes());
        assertEquals(u, b.readUuid());
    }

    @Test
    public void blockPosRoundTrip() {
        BlockPos[] vals = {
            new BlockPos(0, 0, 0),
            new BlockPos(1, 2, 3),
            new BlockPos(-30000000, 255, 30000000),
            new BlockPos(2, -1, -3)
        };
        for (BlockPos p : vals) {
            PacketBuffer b = buf();
            b.writeBlockPos(p);
            assertEquals("BlockPos packs into one long (8 bytes)", 8, b.readableBytes());
            assertEquals("BlockPos round-trip " + p, p, b.readBlockPos());
        }
    }
}
