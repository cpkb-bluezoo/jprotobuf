package org.bluezoo.protobuf;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

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
}
