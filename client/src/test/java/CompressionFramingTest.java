import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.NettyCompressionDecoder;
import net.minecraft.network.NettyCompressionEncoder;
import net.minecraft.network.PacketBuffer;
import org.junit.Test;

/**
 * Golden framing test for the packet-compression codecs. Guards the wire format
 * across Netty upgrades: below the threshold a frame is VarInt(0) + raw bytes;
 * at/above it is VarInt(uncompressedLen) + zlib data. A Netty change that altered
 * ByteBuf/codec behavior would break either the framing byte or the round-trip.
 */
public class CompressionFramingTest {

    private static final int THRESHOLD = 256;

    private static byte[] toBytes(ByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), b);
        return b;
    }

    /** Encode one payload, return the raw framed bytes on the wire. */
    private byte[] encode(byte[] payload) {
        EmbeddedChannel enc = new EmbeddedChannel(new NettyCompressionEncoder(THRESHOLD));
        assertTrue(enc.writeOutbound(Unpooled.wrappedBuffer(payload)));
        ByteBuf framed = enc.readOutbound();
        byte[] wire = toBytes(framed);
        framed.release();
        enc.finishAndReleaseAll();
        return wire;
    }

    /** Decode framed wire bytes back to the payload. */
    private byte[] decode(byte[] wire) {
        EmbeddedChannel dec = new EmbeddedChannel(new NettyCompressionDecoder(THRESHOLD));
        assertTrue(dec.writeInbound(Unpooled.wrappedBuffer(wire)));
        ByteBuf out = dec.readInbound();
        byte[] payload = toBytes(out);
        out.release();
        dec.finishAndReleaseAll();
        return payload;
    }

    @Test
    public void belowThresholdIsUncompressedWithZeroPrefix() {
        byte[] payload = new byte[100]; // < 256
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;

        byte[] wire = encode(payload);
        // First byte is the VarInt uncompressed-length marker; below threshold = 0.
        assertEquals("below-threshold frame must start with VarInt 0", 0, wire[0]);
        // Followed by the raw (uncompressed) payload.
        byte[] rest = new byte[wire.length - 1];
        System.arraycopy(wire, 1, rest, 0, rest.length);
        assertArrayEquals("raw payload after the 0 marker", payload, rest);

        assertArrayEquals("round-trip", payload, decode(wire));
    }

    @Test
    public void aboveThresholdCompressesAndRoundTrips() {
        // Highly-compressible 4KB payload (well above threshold).
        byte[] payload = new byte[4096];
        // some structure so zlib actually compresses
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 16);

        byte[] wire = encode(payload);
        assertTrue("above-threshold frame must not start with 0 marker", wire[0] != 0);
        assertTrue("compressed wire should be smaller than payload", wire.length < payload.length);
        assertArrayEquals("round-trip of compressed payload", payload, decode(wire));
    }

    @Test
    public void randomLargePayloadRoundTrips() {
        byte[] payload = new byte[8000];
        new Random(1234L).nextBytes(payload);
        assertArrayEquals("random payload round-trip", payload, decode(encode(payload)));
    }
}
