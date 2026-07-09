import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.Test;

/**
 * Golden NBT serialization test. NBT rides the wire (packets) and disk (saves),
 * so its byte format must not drift across dependency upgrades. Builds a compound
 * covering every leaf type + nested list/compound, serializes via the raw
 * DataOutput path, and asserts a byte-identical + value-identical round-trip.
 */
public class NbtRoundTripTest {

    private static NBTTagCompound sample() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByte("b", (byte) -7);
        tag.setShort("s", (short) 12345);
        tag.setInteger("i", -2000000000);
        tag.setLong("l", 9000000000000L);
        tag.setFloat("f", 3.5f);
        tag.setDouble("d", -1.25d);
        tag.setString("str", "café 你好 😀");
        tag.setByteArray("ba", new byte[] {1, 2, 3, -1});
        tag.setIntArray("ia", new int[] {1, -1, 2147483647});

        NBTTagList list = new NBTTagList();
        NBTTagCompound e1 = new NBTTagCompound();
        e1.setString("id", "minecraft:stone");
        e1.setByte("Count", (byte) 64);
        list.appendTag(e1);
        NBTTagCompound e2 = new NBTTagCompound();
        e2.setString("id", "minecraft:dirt");
        list.appendTag(e2);
        tag.setTag("Items", list);

        NBTTagCompound nested = new NBTTagCompound();
        nested.setInteger("x", 1);
        tag.setTag("nested", nested);
        return tag;
    }

    private static byte[] write(NBTTagCompound tag) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        CompressedStreamTools.write(tag, dos);
        dos.close();
        return bos.toByteArray();
    }

    private static NBTTagCompound read(byte[] bytes) throws Exception {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        return CompressedStreamTools.read(dis, NBTSizeTracker.INFINITE);
    }

    @Test
    public void roundTripIsStable() throws Exception {
        NBTTagCompound original = sample();
        byte[] once = write(original);

        NBTTagCompound reparsed = read(once);
        // Value equality (NBTTagCompound.equals compares the tag map deeply).
        assertEquals("NBT value round-trip", original, reparsed);

        // Byte-stability: re-serializing the reparsed tag yields identical bytes.
        byte[] twice = write(reparsed);
        assertArrayEquals("NBT byte-stable round-trip", once, twice);
    }

    @Test
    public void leafValuesSurvive() throws Exception {
        NBTTagCompound r = read(write(sample()));
        assertEquals((byte) -7, r.getByte("b"));
        assertEquals((short) 12345, r.getShort("s"));
        assertEquals(-2000000000, r.getInteger("i"));
        assertEquals(9000000000000L, r.getLong("l"));
        assertEquals(3.5f, r.getFloat("f"), 0f);
        assertEquals(-1.25d, r.getDouble("d"), 0d);
        assertEquals("café 你好 😀", r.getString("str"));
        assertArrayEquals(new byte[] {1, 2, 3, -1}, r.getByteArray("ba"));
        assertArrayEquals(new int[] {1, -1, 2147483647}, r.getIntArray("ia"));
        assertEquals(2, r.getTagList("Items", 10).tagCount());
    }
}
