package org.bluezoo.protobuf;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.bluezoo.protobuf.ByteBufferChannel;
import org.bluezoo.protobuf.DefaultProtobufHandler;
import org.bluezoo.protobuf.ProtobufParseException;
import org.bluezoo.protobuf.ProtobufParser;
import org.bluezoo.protobuf.ProtobufHandler;
import org.bluezoo.protobuf.ProtobufWriter;

/**
 * JUnit 4 test class for ProtobufParser.
 * Tests the push-based protobuf parsing.
 */
public class ProtobufParserTest {

    // -- Basic field parsing tests --

    @Test
    public void testParseVarintField() throws Exception {
        // Write field 1 = 150
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeVarintField(1, 150);
            }
        });

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(1, handler.varints.size());
        assertEquals(1, handler.varints.get(0).fieldNumber);
        assertEquals(150L, (long) handler.varints.get(0).value);
    }

    @Test
    public void testParseVarintFieldValueNegativeOne() throws Exception {
        // GHSA-4vx4-8xxq-gvwj: -1 is the canonical, standard-library-produced
        // 10-byte varint encoding for a negative int32/int64 field. The
        // parser must not confuse a fully-present field whose decoded value
        // happens to be -1 with "not enough data yet".
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeVarintField(1, -1L);
                w.writeVarintField(2, 7); // must still be reachable afterwards
            }
        });

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertFalse("a fully-present field must never report underflow", parser.isUnderflow());
        assertEquals(2, handler.varints.size());
        assertEquals(1, handler.varints.get(0).fieldNumber);
        assertEquals(-1L, (long) handler.varints.get(0).value);
        assertEquals(2, handler.varints.get(1).fieldNumber);
        assertEquals(7L, (long) handler.varints.get(1).value);
    }

    @Test
    public void testParseMultipleVarintFields() throws Exception {
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeVarintField(1, 42);
                w.writeVarintField(2, 100);
                w.writeVarintField(3, 256);
            }
        });

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(3, handler.varints.size());
        assertEquals(1, handler.varints.get(0).fieldNumber);
        assertEquals(42L, (long) handler.varints.get(0).value);
        assertEquals(2, handler.varints.get(1).fieldNumber);
        assertEquals(100L, (long) handler.varints.get(1).value);
        assertEquals(3, handler.varints.get(2).fieldNumber);
        assertEquals(256L, (long) handler.varints.get(2).value);
    }

    @Test
    public void testParseBoolField() throws Exception {
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeBoolField(1, true);
                w.writeBoolField(2, false);
            }
        });

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(2, handler.varints.size());
        assertEquals(1L, (long) handler.varints.get(0).value); // true
        assertEquals(0L, (long) handler.varints.get(1).value); // false
    }

    @Test
    public void testParseFixed64Field() throws Exception {
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeFixed64Field(1, 0x0102030405060708L);
            }
        });

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(1, handler.fixed64s.size());
        assertEquals(1, handler.fixed64s.get(0).fieldNumber);
        assertEquals(0x0102030405060708L, (long) handler.fixed64s.get(0).value);
    }

    @Test
    public void testParseFixed32Field() throws Exception {
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeFixed32Field(1, 0x01020304);
            }
        });

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(1, handler.fixed32s.size());
        assertEquals(1, handler.fixed32s.get(0).fieldNumber);
        assertEquals(0x01020304, (int) handler.fixed32s.get(0).value);
    }

    @Test
    public void testParseStringField() throws Exception {
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeStringField(1, "hello");
            }
        });

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(1, handler.bytes.size());
        assertEquals(1, handler.bytes.get(0).fieldNumber);
        assertEquals("hello", new String(handler.bytes.get(0).value, "UTF-8"));
    }

    @Test
    public void testParseBytesField() throws Exception {
        final byte[] testData = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeBytesField(1, testData);
            }
        });

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(1, handler.bytes.size());
        assertEquals(1, handler.bytes.get(0).fieldNumber);
        byte[] parsed = handler.bytes.get(0).value;
        assertEquals(4, parsed.length);
        assertEquals(0x01, parsed[0]);
        assertEquals(0x02, parsed[1]);
        assertEquals(0x03, parsed[2]);
        assertEquals(0x04, parsed[3]);
    }

    // -- Embedded message tests --

    @Test
    public void testParseEmbeddedMessage() throws Exception {
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeVarintField(1, 42); // outer field
                w.writeMessageField(2, new ProtobufWriter.MessageContent() {
                    @Override public void writeTo(ProtobufWriter inner) throws IOException {
                        inner.writeStringField(1, "nested");
                        inner.writeVarintField(2, 100);
                    }
                });
                w.writeVarintField(3, 99); // another outer field
            }
        });

        MessageTrackingHandler handler = new MessageTrackingHandler();
        handler.messageFields.add(2); // field 2 is a message

        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        // Check message events
        assertEquals(1, handler.messageStarts.size());
        assertEquals(2, (int) handler.messageStarts.get(0));
        assertEquals(1, handler.messageEnds);

        // Check parsed fields - push parser sees ALL varints including nested ones
        assertEquals(3, handler.varints.size()); // 42 (outer), 100 (inner), 99 (outer)
        assertEquals("nested", handler.lastString);
    }

    @Test
    public void testParseNestedMessages() throws Exception {
        // Use different field numbers for messages vs leaf fields
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeMessageField(1, new ProtobufWriter.MessageContent() {
                    @Override public void writeTo(ProtobufWriter level1) throws IOException {
                        level1.writeMessageField(2, new ProtobufWriter.MessageContent() {
                            @Override public void writeTo(ProtobufWriter level2) throws IOException {
                                level2.writeStringField(3, "deepest");
                            }
                        });
                    }
                });
            }
        });

        MessageTrackingHandler handler = new MessageTrackingHandler();
        handler.messageFields.add(1); // field 1 is a message
        handler.messageFields.add(2); // field 2 is a message
        // field 3 is a string (not in messageFields)

        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(2, handler.messageStarts.size());
        assertEquals(2, handler.messageEnds);
        assertEquals("deepest", handler.lastString);
    }

    // -- Underflow tests --

    @Test
    public void testUnderflowVarint() throws Exception {
        // Write a multi-byte varint, then split it
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeVarintField(1, 16384); // 3-byte varint
            }
        });

        // Only provide partial data
        ByteBuffer partial = ByteBuffer.allocate(2);
        partial.put(data.get());
        partial.put(data.get());
        partial.flip();

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(partial);

        assertTrue(parser.isUnderflow());
        assertEquals(0, handler.varints.size()); // Nothing parsed yet
    }

    @Test
    public void testUnderflowRecovery() throws Exception {
        // Write two fields
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeVarintField(1, 16384);
                w.writeVarintField(2, 42);
            }
        });

        // Split after first partial tag
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);

        // First chunk: just 2 bytes (partial)
        ByteBuffer chunk1 = ByteBuffer.wrap(bytes, 0, 2);
        // Second chunk: rest of data
        ByteBuffer chunk2 = ByteBuffer.wrap(bytes, 0, bytes.length);

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);

        parser.receive(chunk1);
        assertTrue(parser.isUnderflow());
        assertEquals(0, chunk1.position()); // Position should be at start of incomplete field

        // Simulate compact() + more data
        parser.receive(chunk2);
        assertFalse(parser.isUnderflow());
        parser.close();

        assertEquals(2, handler.varints.size());
    }

    @Test
    public void testCloseWithUnderflowThrows() throws Exception {
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeStringField(1, "hello world");
            }
        });

        // Only provide partial data
        ByteBuffer partial = ByteBuffer.allocate(3);
        partial.put(data.get());
        partial.put(data.get());
        partial.put(data.get());
        partial.flip();

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(partial);

        assertTrue(parser.isUnderflow());

        try {
            parser.close();
            fail("Expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            assertTrue(e.getMessage().contains("Incomplete"));
        }
    }

    // -- Security / limit tests --

    @Test
    public void testLengthDelimitedFieldExceedsEmbeddedMessageBudget() throws Exception {
        // Outer field 2: embedded message, declared length 4 bytes.
        // Inner body claims a 50-byte string but only 2 payload bytes fit.
        ByteBuffer data = ByteBuffer.wrap(new byte[] {
                0x12, 0x04, // field 2, length 4
                0x0A, 0x32, 0x00, 0x00 // field 1 string, length 50, padding
        });

        MessageTrackingHandler handler = new MessageTrackingHandler();
        handler.messageFields.add(2);

        ProtobufParser parser = new ProtobufParser(handler);
        try {
            parser.receive(data);
            fail("Expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            assertTrue(e.getMessage().contains("embedded message"));
        }
    }

    @Test
    public void testMaxMessageDepthExceeded() throws Exception {
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeMessageField(1, new ProtobufWriter.MessageContent() {
                    @Override public void writeTo(ProtobufWriter level1) throws IOException {
                        level1.writeMessageField(1, new ProtobufWriter.MessageContent() {
                            @Override public void writeTo(ProtobufWriter level2) throws IOException {
                                level2.writeVarintField(2, 1);
                            }
                        });
                    }
                });
            }
        });

        MessageTrackingHandler handler = new MessageTrackingHandler();
        handler.messageFields.add(1);

        ProtobufParser parser = new ProtobufParser(handler, 1,
                ProtobufParser.UNLIMITED_LENGTH_DELIMITED);
        try {
            parser.receive(data);
            fail("Expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            assertTrue(e.getMessage().contains("depth"));
        }
    }

    @Test
    public void testMaxLengthDelimitedSizeExceeded() throws Exception {
        ByteBuffer data = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeBytesField(1, new byte[] { 0x01, 0x02, 0x03 });
            }
        });

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler,
                ProtobufParser.DEFAULT_MAX_MESSAGE_DEPTH, 2);
        try {
            parser.receive(data);
            fail("Expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            assertTrue(e.getMessage().contains("Length-delimited"));
        }
    }

    // -- Error handling tests --

    @Test
    public void testInvalidFieldNumber() throws Exception {
        // Manually create a tag with field number 0 (invalid)
        ByteBuffer data = ByteBuffer.allocate(2);
        data.put((byte) 0x00); // tag with field 0, wire type 0
        data.put((byte) 0x01); // value
        data.flip();

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);

        try {
            parser.receive(data);
            fail("Expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            assertTrue(e.getMessage().contains("field number 0"));
        }
    }

    @Test
    public void testUnknownWireType() throws Exception {
        // Create a tag with wire type 3 (deprecated) or 4 (deprecated)
        ByteBuffer data = ByteBuffer.allocate(2);
        data.put((byte) 0x0B); // field 1, wire type 3
        data.put((byte) 0x00);
        data.flip();

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);

        try {
            parser.receive(data);
            fail("Expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            assertTrue(e.getMessage().contains("wire type"));
        }
    }

    // -- Reset test --

    @Test
    public void testReset() throws Exception {
        ByteBuffer data1 = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeVarintField(1, 42);
            }
        });
        ByteBuffer data2 = writeMessage(new MessageWriter() {
            @Override public void write(ProtobufWriter w) throws IOException {
                w.writeVarintField(1, 99);
            }
        });

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);

        parser.receive(data1);
        parser.close();
        assertEquals(1, handler.varints.size());
        assertEquals(42L, (long) handler.varints.get(0).value);

        // Reset and parse new message
        parser.reset();
        handler.varints.clear();

        parser.receive(data2);
        parser.close();
        assertEquals(1, handler.varints.size());
        assertEquals(99L, (long) handler.varints.get(0).value);
    }

    // -- Nested message bounds tests --

    /** Handler that logs every event as a string, with fields 10-19 as messages. */
    private static class EventLog extends DefaultProtobufHandler {
        final List<String> events = new ArrayList<>();

        @Override
        public boolean isMessage(int fieldNumber) {
            return fieldNumber >= 10 && fieldNumber < 20;
        }

        @Override
        public void startMessage(int fieldNumber) {
            events.add("S" + fieldNumber);
        }

        @Override
        public void endMessage() {
            events.add("E");
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
            StringBuilder sb = new StringBuilder("B").append(fieldNumber).append('=');
            while (data.hasRemaining()) {
                sb.append(String.format("%02x", data.get()));
            }
            events.add(sb.toString());
        }
    }

    /** Writes a random message tree, logging the events the parser must emit. */
    private static void generate(Random rnd, ProtobufWriter w, int depth,
            int maxDepth, List<String> events) throws IOException {
        int fields = rnd.nextInt(6);
        for (int i = 0; i < fields; i++) {
            int kind = rnd.nextInt(depth < maxDepth ? 6 : 5);
            int f = 1 + rnd.nextInt(9);
            switch (kind) {
                case 0: {
                    long v = rnd.nextBoolean() ? rnd.nextInt(300) : rnd.nextLong();
                    w.writeVarintField(f, v);
                    events.add("V" + f + "=" + v);
                    break;
                }
                case 1: {
                    long v = rnd.nextLong();
                    w.writeFixed64Field(f, v);
                    events.add("L" + f + "=" + v);
                    break;
                }
                case 2: {
                    int v = rnd.nextInt();
                    w.writeFixed32Field(f, v);
                    events.add("I" + f + "=" + v);
                    break;
                }
                case 3:
                case 4: {
                    byte[] b = new byte[rnd.nextInt(4) == 0 ? rnd.nextInt(300) : rnd.nextInt(8)];
                    rnd.nextBytes(b);
                    w.writeBytesField(f, b);
                    StringBuilder sb = new StringBuilder("B").append(f).append('=');
                    for (byte x : b) {
                        sb.append(String.format("%02x", x));
                    }
                    events.add(sb.toString());
                    break;
                }
                default: {
                    int mf = 10 + rnd.nextInt(10);
                    events.add("S" + mf);
                    w.writeMessageField(mf, inner -> generate(rnd, inner, depth + 1, maxDepth, events));
                    events.add("E");
                }
            }
        }
    }

    private static List<String> parseChunked(byte[] data, Random rnd, int maxChunk)
            throws ProtobufParseException {
        EventLog log = new EventLog();
        ProtobufParser parser = new ProtobufParser(log, 1000, ProtobufParser.UNLIMITED_LENGTH_DELIMITED);
        ByteBuffer buf = ByteBuffer.allocate(data.length + 1);
        int off = 0;
        while (off < data.length) {
            int n = Math.min(data.length - off, 1 + (maxChunk == 1 ? 0 : rnd.nextInt(maxChunk)));
            buf.put(data, off, n);
            off += n;
            buf.flip();
            parser.receive(buf);
            buf.compact();
        }
        parser.close();
        return log.events;
    }

    @Test
    public void testRandomNestedMessagesAcrossArbitraryChunking() throws Exception {
        Random rnd = new Random(20260401L);
        for (int round = 0; round < 300; round++) {
            List<String> expected = new ArrayList<>();
            ByteBufferChannel ch = new ByteBufferChannel(64);
            generate(rnd, new ProtobufWriter(ch), 0, 1 + rnd.nextInt(12), expected);
            byte[] data = ch.toByteArray();

            for (int maxChunk : new int[] {1, 2, 7, 64, data.length + 1}) {
                assertEquals("round " + round + " maxChunk " + maxChunk,
                        expected, parseChunked(data, rnd, maxChunk));
            }
        }
    }

    @Test
    public void testDeeplyNestedMessagesCloseTogether() throws Exception {
        // 60 levels, a field at each level after its child, and a field at
        // depth 0 afterwards: all 60 endMessage events must fire in order.
        int levels = 60;
        List<String> expected = new ArrayList<>();
        ByteBufferChannel ch = new ByteBufferChannel(64);
        ProtobufWriter w = new ProtobufWriter(ch);
        nest(w, levels, expected);
        w.writeVarintField(1, 7);
        expected.add("V1=7");

        assertEquals(expected, parseChunked(ch.toByteArray(), new Random(1), 1));
        assertEquals(expected, parseChunked(ch.toByteArray(), new Random(2), 1000));
    }

    private static void nest(ProtobufWriter w, int levels, List<String> events) throws IOException {
        if (levels == 0) {
            w.writeVarintField(2, levels);
            events.add("V2=0");
            return;
        }
        events.add("S10");
        w.writeMessageField(10, inner -> {
            nest(inner, levels - 1, events);
            inner.writeVarintField(3, levels);
            events.add("V3=" + levels);
        });
        events.add("E");
    }

    @Test
    public void testEmptyEmbeddedMessages() throws Exception {
        byte[] data = {
            0x52, 0x00,              // field 10, empty
            0x52, 0x02, 0x5A, 0x00,  // field 10 { field 11 empty }
            0x08, 0x05               // field 1 = 5 at depth 0
        };
        assertEquals(java.util.Arrays.asList("S10", "E", "S10", "S11", "E", "E", "V1=5"),
                parseChunked(data, new Random(3), 1));
    }

    @Test
    public void testInnerMessageLongerThanParentBudgetRejected() throws Exception {
        // field 10 len 4 { field 11 len 9 ... }: inner claims more than the
        // 2 bytes left in its parent.
        EventLog log = new EventLog();
        ProtobufParser parser = new ProtobufParser(log);
        try {
            parser.receive(ByteBuffer.wrap(new byte[] {
                0x52, 0x04, 0x5A, 0x09, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            }));
            fail("Expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            assertTrue(e.getMessage().contains("embedded message"));
        }
    }

    @Test
    public void testVarintRunningPastInnermostBudgetRejected() throws Exception {
        // The inner message has two bytes: tag 0x08 and a varint byte 0x80
        // with its continuation bit set. A following byte must not be
        // consumed as part of the varint, even though the parent and the
        // buffer both have room for it.
        EventLog log = new EventLog();
        ProtobufParser parser = new ProtobufParser(log);
        try {
            parser.receive(ByteBuffer.wrap(new byte[] {
                0x52, 0x05, 0x5A, 0x02, 0x08, (byte) 0x80, 0x01
            }));
            fail("Expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            assertTrue(e.getMessage().contains("embedded message"));
        }
    }

    @Test
    public void testTruncatedEmbeddedMessageReportedOnClose() throws Exception {
        EventLog log = new EventLog();
        ProtobufParser parser = new ProtobufParser(log);
        parser.receive(ByteBuffer.wrap(new byte[] {
            0x52, 0x0A, 0x5A, 0x04, 0x08, 0x01   // declared 10, only 4 present
        }));
        assertEquals(java.util.Arrays.asList("S10", "S11", "V1=1"), log.events);
        try {
            parser.close();
            fail("Expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            // two messages still open
        }
    }

    @Test
    public void testResetInsideNestedMessage() throws Exception {
        EventLog log = new EventLog();
        ProtobufParser parser = new ProtobufParser(log);
        parser.receive(ByteBuffer.wrap(new byte[] {0x52, 0x08, 0x5A, 0x04, 0x08, 0x01})); // partial
        parser.reset();
        log.events.clear();

        // A fresh, complete document must parse as if new.
        parser.receive(ByteBuffer.wrap(new byte[] {0x52, 0x02, 0x08, 0x09, 0x10, 0x03}));
        parser.close();
        assertEquals(java.util.Arrays.asList("S10", "V1=9", "E", "V2=3"), log.events);
    }

    // -- Varint and fixed-width decoding tests --

    private static ByteBuffer rawBuffer(String kind, byte[] bytes) {
        switch (kind) {
            case "heap":
                return ByteBuffer.wrap(bytes);
            case "direct": {
                ByteBuffer d = ByteBuffer.allocateDirect(bytes.length);
                d.put(bytes).flip();
                return d;
            }
            case "readonly":
                return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
            case "offset": {
                // array offset and non-zero buffer position
                byte[] padded = new byte[bytes.length + 7];
                System.arraycopy(bytes, 0, padded, 5, bytes.length);
                return ByteBuffer.wrap(padded, 5, bytes.length);
            }
            case "slice": {
                byte[] padded = new byte[bytes.length + 7];
                System.arraycopy(bytes, 0, padded, 3, bytes.length);
                ByteBuffer b = ByteBuffer.wrap(padded);
                b.position(3).limit(3 + bytes.length);
                return b.slice();
            }
            default:
                throw new IllegalArgumentException(kind);
        }
    }

    private static final String[] BUFFER_KINDS = {"heap", "direct", "readonly", "offset", "slice"};

    private static byte[] varintBytes(long value) throws IOException {
        ByteBufferChannel ch = new ByteBufferChannel(16);
        new ProtobufWriter(ch).writeVarint(value);
        return ch.toByteArray();
    }

    @Test
    public void testVarintValuesOfEveryLength() throws Exception {
        // Field 1 varint with values straddling every 7-bit boundary, plus
        // negatives (always 10 bytes), through every buffer flavour and
        // fed one byte at a time.
        List<Long> values = new ArrayList<>();
        for (int bits = 0; bits <= 63; bits++) {
            long edge = (1L << bits);
            values.add(edge - 1);
            values.add(edge);
            values.add(edge + 1);
            values.add(-edge);
        }
        values.add(Long.MIN_VALUE);
        values.add(Long.MAX_VALUE);

        for (long v : values) {
            ByteBufferChannel ch = new ByteBufferChannel(16);
            new ProtobufWriter(ch).writeVarintField(1, v);
            byte[] data = ch.toByteArray();

            for (String kind : BUFFER_KINDS) {
                EventLog log = new EventLog();
                ProtobufParser parser = new ProtobufParser(log);
                ByteBuffer buf = rawBuffer(kind, data);
                parser.receive(buf);
                parser.close();
                assertEquals(kind + " " + v, java.util.Arrays.asList("V1=" + v), log.events);
                assertFalse(buf.hasRemaining());
            }

            // Every strict prefix underflows without consuming anything.
            for (int n = 0; n < data.length; n++) {
                EventLog log = new EventLog();
                ProtobufParser parser = new ProtobufParser(log);
                ByteBuffer buf = ByteBuffer.wrap(data, 0, n);
                parser.receive(buf);
                assertEquals("prefix " + n + " of " + v, 0, buf.position());
                assertTrue(log.events.isEmpty());
                assertEquals(n > 0, parser.isUnderflow());
            }
            assertEquals(java.util.Arrays.asList("V1=" + v), parseChunked(data, new Random(5), 1));
        }
    }

    @Test
    public void testMultiByteTagsParse() throws Exception {
        // Field numbers 16, 2048, 262144 and the maximum need 2-5 byte tags.
        int[] fields = {16, 2047, 2048, 262143, 262144, 536870911};
        for (int f : fields) {
            ByteBufferChannel ch = new ByteBufferChannel(16);
            ProtobufWriter w = new ProtobufWriter(ch);
            w.writeVarintField(f, 9);
            w.writeFixed32Field(f, 7);
            byte[] data = ch.toByteArray();
            for (int maxChunk : new int[] {1, 1000}) {
                assertEquals("field " + f,
                        java.util.Arrays.asList("V" + f + "=9", "I" + f + "=7"),
                        parseChunked(data, new Random(f), maxChunk));
            }
        }
    }

    @Test
    public void testVarintTooLongRejected() throws Exception {
        // Ten continuation bytes: no terminator within the 10 permitted.
        byte[] data = new byte[12];
        data[0] = 0x08; // field 1 varint
        for (int i = 1; i <= 10; i++) {
            data[i] = (byte) 0x80;
        }
        data[11] = 0x00;
        for (int n : new int[] {11, 12}) { // rejected as soon as the 10th byte arrives
            ProtobufParser parser = new ProtobufParser(new EventLog());
            try {
                parser.receive(ByteBuffer.wrap(data, 0, n));
                fail("Expected ProtobufParseException for " + n + " bytes");
            } catch (ProtobufParseException e) {
                assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains("varint"));
            }
        }
        // ...but nine continuation bytes is still just incomplete.
        ProtobufParser parser = new ProtobufParser(new EventLog());
        ByteBuffer buf = ByteBuffer.wrap(data, 0, 10);
        parser.receive(buf);
        assertTrue(parser.isUnderflow());
        assertEquals(0, buf.position());
    }

    @Test
    public void testFixedValuesAnyBufferAndByteOrder() throws Exception {
        long[] longs = {0, 1, -1, 0x0807060504030201L, Long.MIN_VALUE, Long.MAX_VALUE, 0xDEADBEEFL};
        int[] ints = {0, 1, -1, 0x04030201, Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (ByteOrder order : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
            for (String kind : BUFFER_KINDS) {
                for (long l : longs) {
                    ByteBufferChannel ch = new ByteBufferChannel(16);
                    new ProtobufWriter(ch).writeFixed64Field(3, l);
                    ByteBuffer buf = rawBuffer(kind, ch.toByteArray());
                    buf.order(order);
                    EventLog log = new EventLog();
                    new ProtobufParser(log).receive(buf);
                    assertEquals(kind + " " + order + " " + l,
                            java.util.Arrays.asList("L3=" + l), log.events);
                    assertEquals("caller's byte order must be left alone", order, buf.order());
                }
                for (int i : ints) {
                    ByteBufferChannel ch = new ByteBufferChannel(16);
                    new ProtobufWriter(ch).writeFixed32Field(3, i);
                    ByteBuffer buf = rawBuffer(kind, ch.toByteArray());
                    buf.order(order);
                    EventLog log = new EventLog();
                    new ProtobufParser(log).receive(buf);
                    assertEquals(kind + " " + order + " " + i,
                            java.util.Arrays.asList("I3=" + i), log.events);
                    assertEquals(order, buf.order());
                }
            }
        }
    }

    // -- Helper methods --

    private interface MessageWriter {
        void write(ProtobufWriter writer) throws IOException;
    }

    private ByteBuffer writeMessage(MessageWriter writer) throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter protoWriter = new ProtobufWriter(channel);
        writer.write(protoWriter);
        return channel.toByteBuffer();
    }

    // -- Helper handler classes --

    private static class RecordingHandler extends DefaultProtobufHandler {
        final List<FieldValue<Long>> varints = new ArrayList<>();
        final List<FieldValue<Long>> fixed64s = new ArrayList<>();
        final List<FieldValue<Integer>> fixed32s = new ArrayList<>();
        final List<FieldValue<byte[]>> bytes = new ArrayList<>();

        @Override
        public void handleVarint(int fieldNumber, long value) {
            varints.add(new FieldValue<>(fieldNumber, value));
        }

        @Override
        public void handleFixed64(int fieldNumber, long value) {
            fixed64s.add(new FieldValue<>(fieldNumber, value));
        }

        @Override
        public void handleFixed32(int fieldNumber, int value) {
            fixed32s.add(new FieldValue<>(fieldNumber, value));
        }

        @Override
        public void handleBytes(int fieldNumber, ByteBuffer data) {
            byte[] arr = new byte[data.remaining()];
            data.get(arr);
            bytes.add(new FieldValue<>(fieldNumber, arr));
        }
    }

    private static class FieldValue<T> {
        final int fieldNumber;
        final T value;

        FieldValue(int fieldNumber, T value) {
            this.fieldNumber = fieldNumber;
            this.value = value;
        }
    }

    private static class MessageTrackingHandler extends DefaultProtobufHandler {
        final List<Integer> messageFields = new ArrayList<>();
        final List<Integer> messageStarts = new ArrayList<>();
        int messageEnds = 0;
        final List<FieldValue<Long>> varints = new ArrayList<>();
        String lastString;

        @Override
        public boolean isMessage(int fieldNumber) {
            return messageFields.contains(fieldNumber);
        }

        @Override
        public void startMessage(int fieldNumber) {
            messageStarts.add(fieldNumber);
        }

        @Override
        public void endMessage() {
            messageEnds++;
        }

        @Override
        public void handleVarint(int fieldNumber, long value) {
            varints.add(new FieldValue<>(fieldNumber, value));
        }

        @Override
        public void handleBytes(int fieldNumber, ByteBuffer data) {
            lastString = asString(data);
        }
    }
}

