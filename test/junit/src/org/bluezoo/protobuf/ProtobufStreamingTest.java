package org.bluezoo.protobuf;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Tests for streamed length-delimited fields: ProtobufHandler.isStreaming,
 * startBytes, bytesContent and endBytes on the parser side, and
 * writeStartBytes, writeBytesContent and writeEndBytes on the writer side.
 */
public class ProtobufStreamingTest {

    // -- Parser: delivery --

    @Test
    public void testFieldLargerThanBufferStreamsInPieces() throws Exception {
        byte[] payload = randomBytes(new Random(1), 5000);
        byte[] data = concat(fieldHeader(2, payload.length), payload);

        StreamLog log = new StreamLog(2);
        ProtobufParser parser = new ProtobufParser(log);
        feed(parser, data, 16);
        parser.close();

        assertEquals(Arrays.asList("S2:5000", "E"), log.eventsWithoutContent());
        assertArrayEquals(payload, log.content());
        assertTrue("the stream arrived in more than one piece", log.pieces > 1);
        assertTrue("no handleBytes for a streamed field", log.whole.isEmpty());
        assertFalse(parser.isUnderflow());
    }

    @Test
    public void testFieldNotOptedInStillArrivesWhole() throws Exception {
        byte[] payload = randomBytes(new Random(2), 100);
        byte[] data = concat(fieldHeader(3, payload.length), payload);

        StreamLog log = new StreamLog(2);
        ProtobufParser parser = new ProtobufParser(log);
        feed(parser, data, 1000);
        parser.close();

        assertEquals(1, log.whole.size());
        assertArrayEquals(payload, log.whole.get(0));
        assertTrue(log.events.isEmpty());
    }

    @Test
    public void testStreamedFieldBetweenOtherFields() throws Exception {
        byte[] payload = randomBytes(new Random(3), 300);
        byte[] data = concat(
                new byte[] {0x08, 0x05},
                fieldHeader(2, payload.length), payload,
                new byte[] {0x10, 0x07});

        for (int chunk : new int[] {1, 2, 5, 64, 1000}) {
            StreamLog log = new StreamLog(2);
            ProtobufParser parser = new ProtobufParser(log);
            feed(parser, data, chunk);
            parser.close();
            assertEquals("chunk " + chunk,
                    Arrays.asList("V1=5", "S2:300", "E", "V2=7"), log.eventsWithoutContent());
            assertArrayEquals("chunk " + chunk, payload, log.content());
        }
    }

    @Test
    public void testZeroLengthFieldHasNoContent() throws Exception {
        byte[] data = {0x12, 0x00, 0x08, 0x01};

        StreamLog log = new StreamLog(2);
        ProtobufParser parser = new ProtobufParser(log);
        feed(parser, data, 1);
        parser.close();

        assertEquals(Arrays.asList("S2:0", "E", "V1=1"), log.eventsWithoutContent());
        assertEquals(0, log.pieces);
    }

    @Test
    public void testStreamedFieldInsideNestedMessageEndsItAfterwards() throws Exception {
        byte[] payload = randomBytes(new Random(4), 50);
        // field 10 { field 2 = payload }, then field 1 = 9 at depth 0
        byte[] inner = concat(fieldHeader(2, payload.length), payload);
        byte[] data = concat(fieldHeader(10, inner.length), inner, new byte[] {0x08, 0x09});

        for (int chunk : new int[] {1, 3, 1000}) {
            StreamLog log = new StreamLog(2);
            log.messageField = 10;
            ProtobufParser parser = new ProtobufParser(log);
            feed(parser, data, chunk);
            parser.close();
            assertEquals("chunk " + chunk,
                    Arrays.asList("M10", "S2:50", "E", "ME", "V1=9"), log.eventsWithoutContent());
            assertArrayEquals("chunk " + chunk, payload, log.content());
        }
    }

    @Test
    public void testRandomChunkingOfStreamedFields() throws Exception {
        Random rnd = new Random(20261101L);
        for (int round = 0; round < 200; round++) {
            byte[] payload = randomBytes(rnd, rnd.nextInt(600));
            byte[] data = concat(new byte[] {0x08, 0x01}, fieldHeader(2, payload.length),
                    payload, fieldHeader(2, 3), new byte[] {1, 2, 3});
            for (int maxChunk : new int[] {1, 2, 7, 64, data.length + 1}) {
                StreamLog log = new StreamLog(2);
                ProtobufParser parser = new ProtobufParser(log);
                feed(parser, data, maxChunk, rnd);
                parser.close();
                assertArrayEquals("round " + round + " chunk " + maxChunk,
                        concat(payload, new byte[] {1, 2, 3}), log.content());
                assertEquals("round " + round + " chunk " + maxChunk,
                        Arrays.asList("V1=1", "S2:" + payload.length, "E", "S2:3", "E"),
                        log.eventsWithoutContent());
            }
        }
    }

    // -- Parser: what the handler leaves --

    @Test
    public void testBytesLeftByHandlerAreOfferedAgainWithNextPiece() throws Exception {
        byte[] payload = new byte[100];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        byte[] data = concat(fieldHeader(2, payload.length), payload);

        // Consumes all but the last 3 bytes of each piece: those must come
        // back at the front of the next one.
        StreamLog log = new StreamLog(2);
        log.keep = 3;
        ProtobufParser parser = new ProtobufParser(log);
        feed(parser, data, 10);
        parser.close();

        // What the handler leaves of the final piece is dropped: the last 3 bytes.
        assertArrayEquals(Arrays.copyOf(payload, payload.length - 3), log.content());
        assertTrue("pieces overlap the bytes left", log.pieces > 10);
        assertEquals(Arrays.asList("S2:100", "E"), log.eventsWithoutContent());
    }

    @Test
    public void testBytesLeftOfFinalPieceAreDropped() throws Exception {
        byte[] payload = {1, 2, 3, 4};
        byte[] data = concat(fieldHeader(2, payload.length), payload, new byte[] {0x08, 0x05});

        StreamLog log = new StreamLog(2);
        log.keep = 4; // consumes nothing
        ProtobufParser parser = new ProtobufParser(log);
        ByteBuffer buf = ByteBuffer.wrap(data);
        parser.receive(buf);
        parser.close();

        assertEquals(Arrays.asList("S2:4", "E", "V1=5"), log.eventsWithoutContent());
        assertFalse(buf.hasRemaining());
    }

    @Test
    public void testHandlerThatLeavesBytesLeavesThemInCallersBuffer() throws Exception {
        byte[] payload = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        byte[] data = concat(fieldHeader(2, payload.length), payload);

        StreamLog log = new StreamLog(2);
        log.keep = 2;
        ProtobufParser parser = new ProtobufParser(log);
        ByteBuffer buf = ByteBuffer.wrap(data, 0, 7); // header + 5 bytes
        parser.receive(buf);

        assertEquals("bytes the handler left stay in the buffer", 2, buf.remaining());
        assertTrue(parser.isUnderflow());
        try {
            parser.close();
            fail("a field still short of its length must not close cleanly");
        } catch (ProtobufParseException e) {
            // expected
        }
    }

    // -- Parser: state --

    @Test
    public void testUnderflowWhileStreamIsOpen() throws Exception {
        StreamLog log = new StreamLog(2);
        ProtobufParser parser = new ProtobufParser(log);
        parser.receive(ByteBuffer.wrap(new byte[] {0x12, 0x05, 1, 2}));
        assertTrue(parser.isUnderflow());
        try {
            parser.close();
            fail("expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            // expected
        }
        parser.receive(ByteBuffer.wrap(new byte[] {3, 4, 5}));
        assertFalse(parser.isUnderflow());
        parser.close();
        assertEquals(Arrays.asList("S2:5", "E"), log.eventsWithoutContent());
    }

    @Test
    public void testResetAbandonsOpenStream() throws Exception {
        StreamLog log = new StreamLog(2);
        ProtobufParser parser = new ProtobufParser(log);
        parser.receive(ByteBuffer.wrap(new byte[] {0x12, 0x05, 1, 2}));
        parser.reset();
        parser.receive(ByteBuffer.wrap(new byte[] {0x08, 0x01}));
        parser.close();
        assertEquals(Arrays.asList("S2:5", "V1=1"), log.eventsWithoutContent());
    }

    // -- Parser: limits --

    @Test
    public void testStreamedFieldIsNotBoundedByLengthLimit() throws Exception {
        byte[] payload = randomBytes(new Random(5), 1000);
        byte[] data = concat(fieldHeader(2, payload.length), payload);

        StreamLog log = new StreamLog(2);
        ProtobufParser parser = new ProtobufParser(log, 10, 64);
        feed(parser, data, 50);
        parser.close();
        assertArrayEquals(payload, log.content());

        // The same limit still rejects a field delivered whole.
        byte[] other = concat(fieldHeader(3, payload.length), payload);
        try {
            feed(new ProtobufParser(new StreamLog(2), 10, 64), other, 50);
            fail("expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            // expected
        }
    }

    @Test
    public void testStreamedFieldCannotOverrunItsMessage() throws Exception {
        // field 10 declares 4 bytes, but the streamed field inside claims 9
        byte[] data = {0x52, 0x04, 0x12, 0x09, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        StreamLog log = new StreamLog(2);
        log.messageField = 10;
        try {
            new ProtobufParser(log).receive(ByteBuffer.wrap(data));
            fail("expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            assertTrue(e.getMessage().contains("embedded message"));
        }
        assertTrue("no stream started", log.events.indexOf("S2:9") < 0);
    }

    @Test
    public void testMessageTakesPrecedenceOverStreaming() throws Exception {
        StreamLog log = new StreamLog(10);
        log.messageField = 10;
        ProtobufParser parser = new ProtobufParser(log);
        parser.receive(ByteBuffer.wrap(new byte[] {0x52, 0x02, 0x08, 0x01}));
        parser.close();
        assertEquals(Arrays.asList("M10", "V1=1", "ME"), log.eventsWithoutContent());
    }

    // -- Writer --

    @Test
    public void testStreamedWriteMatchesWriteBytesField() throws Exception {
        byte[] payload = randomBytes(new Random(6), 777);

        ByteBufferChannel expected = new ByteBufferChannel(64);
        new ProtobufWriter(expected).writeBytesField(5, payload);

        ByteBufferChannel actual = new ByteBufferChannel(64);
        ProtobufWriter writer = new ProtobufWriter(actual);
        writer.writeStartBytes(5, payload.length);
        writer.writeBytesContent(ByteBuffer.wrap(payload, 0, 100));
        writer.writeBytesContent(ByteBuffer.wrap(payload, 100, 600));
        writer.writeBytesContent(ByteBuffer.wrap(payload, 700, 77));
        writer.writeEndBytes();

        assertArrayEquals(expected.toByteArray(), actual.toByteArray());
        assertEquals(expected.toByteArray().length, writer.getBytesWritten());
    }

    @Test
    public void testStreamedWriteConsumesBuffer() throws Exception {
        ProtobufWriter writer = new ProtobufWriter(new ByteBufferChannel(64));
        writer.writeStartBytes(1, 4);
        ByteBuffer content = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
        writer.writeBytesContent(content);
        assertFalse(content.hasRemaining());
        writer.writeEndBytes();
    }

    @Test
    public void testStreamedWriteOfEmptyField() throws Exception {
        ByteBufferChannel channel = new ByteBufferChannel(64);
        ProtobufWriter writer = new ProtobufWriter(channel);
        writer.writeStartBytes(2, 0);
        writer.writeEndBytes();
        writer.writeVarintField(1, 1);
        assertArrayEquals(new byte[] {0x12, 0x00, 0x08, 0x01}, channel.toByteArray());
    }

    @Test
    public void testStreamedWriteRefusesMoreThanDeclared() throws Exception {
        ByteBufferChannel channel = new ByteBufferChannel(64);
        ProtobufWriter writer = new ProtobufWriter(channel);
        writer.writeStartBytes(1, 3);
        int before = channel.toByteArray().length;
        ByteBuffer tooMuch = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
        try {
            writer.writeBytesContent(tooMuch);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
        assertEquals("nothing of a refused piece is written", before, channel.toByteArray().length);
        assertEquals(4, tooMuch.remaining());
    }

    @Test
    public void testStreamedWriteRefusesShortField() throws Exception {
        ProtobufWriter writer = new ProtobufWriter(new ByteBufferChannel(64));
        writer.writeStartBytes(1, 3);
        writer.writeBytesContent(ByteBuffer.wrap(new byte[] {1, 2}));
        try {
            writer.writeEndBytes();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void testOtherWritesRefusedWhileFieldIsOpen() throws Exception {
        ProtobufWriter writer = new ProtobufWriter(new ByteBufferChannel(64));
        writer.writeStartBytes(1, 3);
        try {
            writer.writeVarintField(2, 1);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            // expected
        }
        try {
            writer.writeStartBytes(3, 1);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            // expected
        }
        writer.writeBytesContent(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        writer.writeEndBytes();
        writer.writeVarintField(2, 1); // fine again
    }

    @Test
    public void testContentOrEndWithoutStartRefused() throws Exception {
        ProtobufWriter writer = new ProtobufWriter(new ByteBufferChannel(64));
        try {
            writer.writeBytesContent(ByteBuffer.wrap(new byte[] {1}));
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            // expected
        }
        try {
            writer.writeEndBytes();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void testNegativeDeclaredLengthRefused() throws Exception {
        ProtobufWriter writer = new ProtobufWriter(new ByteBufferChannel(64));
        try {
            writer.writeStartBytes(1, -1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void testStreamingWriterFeedsStreamingParser() throws Exception {
        byte[] payload = randomBytes(new Random(7), 4000);
        ByteBufferChannel channel = new ByteBufferChannel(64);
        ProtobufWriter writer = new ProtobufWriter(channel);
        writer.writeVarintField(1, 42);
        writer.writeStartBytes(2, payload.length);
        for (int off = 0; off < payload.length; off += 333) {
            writer.writeBytesContent(
                    ByteBuffer.wrap(payload, off, Math.min(333, payload.length - off)));
        }
        writer.writeEndBytes();

        StreamLog log = new StreamLog(2);
        ProtobufParser parser = new ProtobufParser(log);
        feed(parser, channel.toByteArray(), 100);
        parser.close();

        assertEquals(Arrays.asList("V1=42", "S2:4000", "E"), log.eventsWithoutContent());
        assertArrayEquals(payload, log.content());
    }

    // -- Helpers --

    private static void feed(ProtobufParser parser, byte[] data, int chunk)
            throws ProtobufParseException {
        feed(parser, data, chunk, null);
    }

    /**
     * Feeds data in chunks through a buffer too small for it, the way a
     * channel reader would: receive, compact, read more. A chunk is exactly
     * chunk bytes, or a random size up to it when rnd is given.
     */
    private static void feed(ProtobufParser parser, byte[] data, int chunk, Random rnd)
            throws ProtobufParseException {
        ByteBuffer buf = ByteBuffer.allocate(Math.max(chunk, 16) + 16);
        int off = 0;
        while (off < data.length) {
            int n = rnd == null ? chunk : 1 + rnd.nextInt(chunk);
            n = Math.min(n, Math.min(data.length - off, buf.remaining()));
            buf.put(data, off, n);
            off += n;
            buf.flip();
            parser.receive(buf);
            buf.compact();
        }
    }

    private static byte[] randomBytes(Random rnd, int length) {
        byte[] b = new byte[length];
        rnd.nextBytes(b);
        return b;
    }

    private static byte[] fieldHeader(int field, int length) {
        ByteBufferChannel ch = new ByteBufferChannel(16);
        try {
            ProtobufWriter w = new ProtobufWriter(ch);
            w.writeTag(field, ProtobufWriter.WIRETYPE_LEN);
            w.writeVarint(length);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return ch.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    /**
     * Records streaming events. Content pieces are logged as "C" events;
     * eventsWithoutContent() drops them. With keep set, the handler leaves
     * that many trailing bytes of every piece unconsumed.
     */
    private static class StreamLog implements ProtobufHandler {
        final int streamedField;
        int messageField = -1;
        int keep;
        int pieces;
        final List<String> events = new ArrayList<>();
        final List<byte[]> whole = new ArrayList<>();
        // just what the handler consumed of each piece
        final List<byte[]> consumed = new ArrayList<>();

        StreamLog(int streamedField) {
            this.streamedField = streamedField;
        }

        List<String> eventsWithoutContent() {
            return events;
        }

        /** Everything the handler consumed, in order. */
        byte[] content() {
            return concat(consumed.toArray(new byte[0][]));
        }

        @Override
        public boolean isStreaming(int fieldNumber) {
            return fieldNumber == streamedField;
        }

        @Override
        public void startBytes(int fieldNumber, int length) {
            events.add("S" + fieldNumber + ":" + length);
        }

        @Override
        public void bytesContent(ByteBuffer data) {
            pieces++;
            byte[] piece = new byte[data.remaining()];
            data.duplicate().get(piece);
            int take = Math.max(0, piece.length - keep);
            // Bytes the handler keeps are offered again with the next piece,
            // so only what it consumes counts towards the field's content.
            byte[] taken = Arrays.copyOf(piece, take);
            consumed.add(taken);
            data.position(data.position() + take);
        }

        @Override
        public void endBytes() {
            events.add("E");
        }

        @Override
        public boolean isMessage(int fieldNumber) {
            return fieldNumber == messageField;
        }

        @Override
        public void startMessage(int fieldNumber) {
            events.add("M" + fieldNumber);
        }

        @Override
        public void endMessage() {
            events.add("ME");
        }

        @Override
        public void handleVarint(int fieldNumber, long value) {
            events.add("V" + fieldNumber + "=" + value);
        }

        @Override
        public void handleFixed64(int fieldNumber, long value) {
            events.add("L" + fieldNumber + "=" + value);
        }

        @Override
        public void handleFixed32(int fieldNumber, int value) {
            events.add("I" + fieldNumber + "=" + value);
        }

        @Override
        public void handleBytes(int fieldNumber, ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            whole.add(b);
        }
    }
}
