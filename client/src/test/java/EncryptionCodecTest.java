import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.security.Key;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.NettyEncryptingDecoder;
import net.minecraft.network.NettyEncryptingEncoder;
import net.minecraft.util.CryptManager;
import org.junit.Test;

/**
 * Golden loopback test for the protocol-encryption codecs (AES/CFB8/NoPadding),
 * the branch the live ServerJoinTest never exercises (it connects to an
 * offline-mode server, which skips the entire encryption handshake).
 *
 * <p>Two things are locked here:
 * <ul>
 *   <li><b>Wire bytes:</b> encrypting a fixed plaintext under a fixed key/IV must
 *       yield a byte-for-byte fixed ciphertext. Any change to the cipher
 *       transformation, IV derivation, or codec byte handling would break this.</li>
 *   <li><b>Netty 4.2 allocator safety:</b> the codecs run inside a real
 *       {@link EmbeddedChannel}, so {@code NettyEncryptionTranslator.decipher}'s
 *       {@code ctx.alloc().heapBuffer(...).array()} path executes against the
 *       actual channel allocator — the code most at risk from Netty 4.2's
 *       adaptive/MemorySegment buffer rework.</li>
 * </ul>
 *
 * The 16-byte key is {@code 00 01 02 ... 0F} and, per vanilla 1.8.9
 * ({@code CryptManager.createNetCipherInstance}), the IV equals the key bytes.
 * Pure unit test — no game boot, no network, no server.
 */
public class EncryptionCodecTest {

    /** Fixed 128-bit AES key 00 01 ... 0F; IV = key bytes (vanilla behavior). */
    private static Key key() {
        byte[] k = new byte[16];
        for (int i = 0; i < 16; i++) k[i] = (byte) i;
        return new SecretKeySpec(k, "AES");
    }

    private static byte[] toBytes(ByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), b);
        return b;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    /** Push a plaintext ByteBuf through the encrypting encoder, return wire bytes. */
    private byte[] encrypt(byte[] plain) {
        EmbeddedChannel enc = new EmbeddedChannel(
                new NettyEncryptingEncoder(CryptManager.createNetCipherInstance(Cipher.ENCRYPT_MODE, key())));
        assertTrue(enc.writeOutbound(Unpooled.wrappedBuffer(plain)));
        ByteBuf out = enc.readOutbound();
        byte[] wire = toBytes(out);
        out.release();
        enc.finishAndReleaseAll();
        return wire;
    }

    /** Push ciphertext through the decrypting decoder, return recovered plaintext. */
    private byte[] decrypt(byte[] cipherText) {
        EmbeddedChannel dec = new EmbeddedChannel(
                new NettyEncryptingDecoder(CryptManager.createNetCipherInstance(Cipher.DECRYPT_MODE, key())));
        assertTrue(dec.writeInbound(Unpooled.wrappedBuffer(cipherText)));
        ByteBuf out = dec.readInbound();
        byte[] plain = toBytes(out);
        out.release();
        dec.finishAndReleaseAll();
        return plain;
    }

    @Test
    public void encryptProducesGoldenCiphertext() {
        byte[] plain = "The quick brown fox jumps over 13 lazy dogs.".getBytes();
        // Golden AES/CFB8/NoPadding output for key=IV=00..0F over the plaintext above.
        // CFB8 is stream-like, so ciphertext length == plaintext length (44 bytes).
        String golden = "5EB44639981B73C74762C48F7D973120307C8F1AA88B0E6DB3"
                      + "0E4ABDA493939C1F6B4B9B2F3320D6866A5856";
        assertEquals("AES/CFB8 ciphertext must be byte-for-byte stable", golden, hex(encrypt(plain)));
    }

    @Test
    public void encryptThenDecryptRoundTrips() {
        byte[] plain = "The quick brown fox jumps over 13 lazy dogs.".getBytes();
        assertArrayEquals("encrypt->decrypt must recover the plaintext", plain, decrypt(encrypt(plain)));
    }

    @Test
    public void decryptGoldenRecoversPlaintext() {
        // Exercises the decipher() heapBuffer().array() path against the real
        // EmbeddedChannel allocator (the Netty 4.2 buffer-rework risk).
        byte[] plain = "The quick brown fox jumps over 13 lazy dogs.".getBytes();
        String golden = "5EB44639981B73C74762C48F7D973120307C8F1AA88B0E6DB3"
                      + "0E4ABDA493939C1F6B4B9B2F3320D6866A5856";
        byte[] cipherText = new byte[golden.length() / 2];
        for (int i = 0; i < cipherText.length; i++) {
            cipherText[i] = (byte) Integer.parseInt(golden.substring(i * 2, i * 2 + 2), 16);
        }
        assertArrayEquals("decrypting the golden ciphertext yields the plaintext", plain, decrypt(cipherText));
    }

    @Test
    public void largeRandomPayloadRoundTrips() {
        // CFB8 keeps cipher state across calls, so a single large buffer must
        // still round-trip cleanly through both codecs.
        byte[] plain = new byte[9001];
        new Random(20260710L).nextBytes(plain);
        assertArrayEquals("large random payload round-trip", plain, decrypt(encrypt(plain)));
    }
}
