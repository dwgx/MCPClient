package net.marcloud.mcp.core.mm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/**
 * Regression (review finding, test-gap G2): ValueCodec must NOT silently
 * truncate/wrap out-of-range numeric narrowing coercions. Before the fix,
 * {@code Number.intValue()/shortValue()/byteValue()} wrapped: long 300 -> byte 44,
 * so a C5 field write the AI requested as 300 silently stored 44. Now an
 * out-of-range or non-integral value is rejected with MmAccessException.
 *
 * <p>Non-vacuous: on the pre-fix code the assertThrows cases returned a wrapped
 * value instead of throwing, so those assertions FAIL.
 */
public class ValueCodecNarrowingTest {

    @Test
    public void byteOutOfRangeIsRejectedNotWrapped() {
        // 300 as a byte silently became 44 before the fix.
        assertThrows(MmAccessException.class,
                () -> ValueCodec.coerce(byte.class, 300L, null));
        assertThrows(MmAccessException.class,
                () -> ValueCodec.coerce(byte.class, -200, null));
    }

    @Test
    public void shortOutOfRangeIsRejected() {
        assertThrows(MmAccessException.class,
                () -> ValueCodec.coerce(short.class, 40000, null));
    }

    @Test
    public void intOutOfRangeFromLongIsRejected() {
        // 3_000_000_000 wrapped to a negative int before the fix.
        assertThrows(MmAccessException.class,
                () -> ValueCodec.coerce(int.class, 3_000_000_000L, null));
    }

    @Test
    public void fractionalValueToIntegerFieldIsRejectedNotTruncated() {
        // 3.7 -> int silently became 3 before the fix.
        assertThrows(MmAccessException.class,
                () -> ValueCodec.coerce(int.class, 3.7d, null));
    }

    @Test
    public void inRangeValuesStillCoerceCorrectly() {
        assertEquals(300, ValueCodec.coerce(int.class, 300L, null));
        assertEquals((byte) 44, ValueCodec.coerce(byte.class, 44, null));
        assertEquals((short) 1000, ValueCodec.coerce(short.class, 1000L, null));
        // integral-valued double to an int field is fine (5.0 -> 5)
        assertEquals(5, ValueCodec.coerce(int.class, 5.0d, null));
        // long still takes any long
        assertEquals(3_000_000_000L, ValueCodec.coerce(long.class, 3_000_000_000L, null));
    }
}
