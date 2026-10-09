package org.bluezoo.protobuf;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * JUnit 4 test class for ByteBufferChannel.
 */
public class ByteBufferChannelTest {

    @Test(timeout = 5000)
    public void testZeroInitialCapacityGrows() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(0);
        channel.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertArrayEquals(new byte[] {1, 2, 3}, channel.toByteArray());
    }

    @Test(timeout = 5000)
    public void testZeroLeadingReserveAndPayloadGrows() throws IOException {
        ByteBufferChannel channel = ByteBufferChannel.withLeadingReserve(0, 0);
        channel.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertEquals(3, channel.payloadLength());
    }

    @Test(timeout = 5000)
    public void testLeadingReserveWithZeroPayloadCapacityGrows() throws IOException {
        ByteBufferChannel channel = ByteBufferChannel.withLeadingReserve(5, 0);
        channel.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertEquals(3, channel.payloadLength());
        assertEquals(8, channel.size());
    }

    // -- Capacity growth limits --

    private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;

    @Test(timeout = 5000)
    public void testGrowthDoublesNormally() throws IOException {
        assertEquals(32, ByteBufferChannel.grownCapacity(16, 17));
        assertEquals(2048, ByteBufferChannel.grownCapacity(1024, 1025));
        assertEquals(8, ByteBufferChannel.grownCapacity(0, 5));
        assertEquals(1 << 30, ByteBufferChannel.grownCapacity(1 << 29, (1 << 29) + 1));
    }

    @Test(timeout = 5000)
    public void testGrowthPastOneGiBIsCappedNotOverflowed() throws IOException {
        // Doubling 2^30 overflows int; this used to wrap to MIN_VALUE, then 0,
        // and loop forever.
        assertEquals(MAX_CAPACITY, ByteBufferChannel.grownCapacity(1 << 30, (1L << 30) + 1));
        assertEquals(MAX_CAPACITY, ByteBufferChannel.grownCapacity(1 << 30, MAX_CAPACITY));
        assertEquals(MAX_CAPACITY, ByteBufferChannel.grownCapacity(MAX_CAPACITY, MAX_CAPACITY));
    }

    @Test(timeout = 5000)
    public void testGrowthBeyondMaximumArraySizeFails() {
        for (long required : new long[] {MAX_CAPACITY + 1L, Integer.MAX_VALUE, 1L << 32, 1L << 40}) {
            try {
                ByteBufferChannel.grownCapacity(16, required);
                fail("expected IOException for required=" + required);
            } catch (IOException expected) {
                // good: fail rather than spin or wrap
            }
        }
    }
}
