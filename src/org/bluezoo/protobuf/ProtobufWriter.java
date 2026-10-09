/*
 * ProtobufWriter.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of jprotobuf, a Protocol Buffers codec for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/jprotobuf/
 *
 * jprotobuf is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * jprotobuf is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with jprotobuf.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.protobuf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * Zero-dependency Protobuf binary encoder.
 * Implements the wire format as per https://protobuf.dev/programming-guides/encoding/
 *
 * <p>Wire types:
 * <ul>
 *   <li>0 = VARINT (int32, int64, uint32, uint64, sint32, sint64, bool, enum)</li>
 *   <li>1 = I64 (fixed64, sfixed64, double)</li>
 *   <li>2 = LEN (string, bytes, embedded messages, packed repeated fields)</li>
 *   <li>5 = I32 (fixed32, sfixed32, float)</li>
 * </ul>
 *
 * <p>This writer outputs to a {@link WritableByteChannel}, handling non-blocking
 * channels by retrying writes until all bytes are written. For writing to a
 * {@link ByteBuffer}, use {@link ByteBufferChannel}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ProtobufWriter {

    /**
     * Wire type for variable-length integers.
     */
    public static final int WIRETYPE_VARINT = 0;

    /**
     * Wire type for 64-bit fixed values.
     */
    public static final int WIRETYPE_I64 = 1;

    /**
     * Wire type for length-delimited values (strings, bytes, messages).
     */
    public static final int WIRETYPE_LEN = 2;

    /**
     * Wire type for 32-bit fixed values.
     */
    public static final int WIRETYPE_I32 = 5;

    /**
     * Size of the scratch buffer. Large enough that a tag, a length and a
     * short payload go out in a single channel write.
     */
    private static final int WRITE_BUFFER_SIZE = 128;

    /**
     * Longest string (in chars) encoded straight into the scratch buffer
     * when it is pure ASCII: a tag (at most 5 bytes) and a one-byte length
     * (under 128) leave room for it within {@link #WRITE_BUFFER_SIZE}.
     */
    private static final int MAX_INLINE_STRING_CHARS = 100;

    /** Initial capacity of the scratch channel used for embedded messages. */
    private static final int MESSAGE_SCRATCH_INITIAL_CAPACITY = 256;

    /**
     * Scratch channels that grew beyond this many bytes are discarded after
     * use rather than retained, so one large message does not pin memory.
     */
    private static final int MESSAGE_SCRATCH_MAX_RETAINED = 64 * 1024;

    private final WritableByteChannel channel;
    private final ByteBuffer writeBuffer;
    private long bytesWritten;

    // Reusable writer (and its backing channel) for the embedded message
    // currently being built by writeMessageField(); one per nesting level.
    private ByteBufferChannel messageChannel;
    private ProtobufWriter messageWriter;
    private boolean messageInProgress;

    /**
     * Creates a new ProtobufWriter that writes to the given channel.
     *
     * @param channel the channel to write to
     */
    public ProtobufWriter(WritableByteChannel channel) {
        this.channel = channel;
        // Little-endian so fixed-width values go in with a single put; the
        // varint encoding below is byte-at-a-time and unaffected.
        this.writeBuffer = ByteBuffer.allocate(WRITE_BUFFER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        this.bytesWritten = 0;
    }

    /**
     * Returns the number of bytes written so far.
     *
     * @return the byte count
     */
    public long getBytesWritten() {
        return bytesWritten;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tag writing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a field tag.
     * Tag format: (fieldNumber &lt;&lt; 3) | wireType
     *
     * @param fieldNumber the field number (1-based)
     * @param wireType the wire type
     * @throws IOException if an I/O error occurs
     */
    public void writeTag(int fieldNumber, int wireType) throws IOException {
        writeVarint(tagValue(fieldNumber, wireType));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Varint encoding
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes an unsigned varint (base-128 encoding).
     * Each byte has 7 bits of data and MSB as continuation bit.
     *
     * @param value the value to write
     * @throws IOException if an I/O error occurs
     */
    public void writeVarint(long value) throws IOException {
        writeBuffer.clear();
        putVarint(writeBuffer, value);
        flushWriteBuffer();
    }

    /**
     * Writes a signed varint using ZigZag encoding.
     * Maps signed to unsigned: 0→0, -1→1, 1→2, -2→3, etc.
     *
     * @param value the signed value to write
     * @throws IOException if an I/O error occurs
     */
    public void writeSVarint(long value) throws IOException {
        writeVarint((value << 1) ^ (value >> 63));
    }

    /**
     * Writes a signed 32-bit varint using ZigZag encoding.
     *
     * @param value the signed value to write
     * @throws IOException if an I/O error occurs
     */
    public void writeSVarint32(int value) throws IOException {
        writeVarint(((value << 1) ^ (value >> 31)) & 0xFFFFFFFFL);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fixed-size types
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a fixed 64-bit value in little-endian order.
     *
     * @param value the value to write
     * @throws IOException if an I/O error occurs
     */
    public void writeFixed64(long value) throws IOException {
        writeBuffer.clear();
        putFixed64(writeBuffer, value);
        flushWriteBuffer();
    }

    /**
     * Writes a fixed 32-bit value in little-endian order.
     *
     * @param value the value to write
     * @throws IOException if an I/O error occurs
     */
    public void writeFixed32(int value) throws IOException {
        writeBuffer.clear();
        putFixed32(writeBuffer, value);
        flushWriteBuffer();
    }

    /**
     * Writes a double value.
     *
     * @param value the value to write
     * @throws IOException if an I/O error occurs
     */
    public void writeDouble(double value) throws IOException {
        writeFixed64(Double.doubleToRawLongBits(value));
    }

    /**
     * Writes a float value.
     *
     * @param value the value to write
     * @throws IOException if an I/O error occurs
     */
    public void writeFloat(float value) throws IOException {
        writeFixed32(Float.floatToRawIntBits(value));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Field writers (tag + value)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a varint field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @throws IOException if an I/O error occurs
     */
    public void writeVarintField(int fieldNumber, long value) throws IOException {
        writeBuffer.clear();
        putTag(writeBuffer, fieldNumber, WIRETYPE_VARINT);
        putVarint(writeBuffer, value);
        flushWriteBuffer();
    }

    /**
     * Writes a signed varint field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @throws IOException if an I/O error occurs
     */
    public void writeSVarintField(int fieldNumber, long value) throws IOException {
        writeBuffer.clear();
        putTag(writeBuffer, fieldNumber, WIRETYPE_VARINT);
        putVarint(writeBuffer, (value << 1) ^ (value >> 63));
        flushWriteBuffer();
    }

    /**
     * Writes a boolean field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @throws IOException if an I/O error occurs
     */
    public void writeBoolField(int fieldNumber, boolean value) throws IOException {
        writeBuffer.clear();
        putTag(writeBuffer, fieldNumber, WIRETYPE_VARINT);
        putVarint(writeBuffer, value ? 1 : 0);
        flushWriteBuffer();
    }

    /**
     * Writes a fixed64 field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @throws IOException if an I/O error occurs
     */
    public void writeFixed64Field(int fieldNumber, long value) throws IOException {
        writeBuffer.clear();
        putTag(writeBuffer, fieldNumber, WIRETYPE_I64);
        putFixed64(writeBuffer, value);
        flushWriteBuffer();
    }

    /**
     * Writes a fixed32 field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @throws IOException if an I/O error occurs
     */
    public void writeFixed32Field(int fieldNumber, int value) throws IOException {
        writeBuffer.clear();
        putTag(writeBuffer, fieldNumber, WIRETYPE_I32);
        putFixed32(writeBuffer, value);
        flushWriteBuffer();
    }

    /**
     * Writes a double field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @throws IOException if an I/O error occurs
     */
    public void writeDoubleField(int fieldNumber, double value) throws IOException {
        writeBuffer.clear();
        putTag(writeBuffer, fieldNumber, WIRETYPE_I64);
        putFixed64(writeBuffer, Double.doubleToRawLongBits(value));
        flushWriteBuffer();
    }

    /**
     * Writes a float field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @throws IOException if an I/O error occurs
     */
    public void writeFloatField(int fieldNumber, float value) throws IOException {
        writeBuffer.clear();
        putTag(writeBuffer, fieldNumber, WIRETYPE_I32);
        putFixed32(writeBuffer, Float.floatToRawIntBits(value));
        flushWriteBuffer();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Length-delimited types
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes raw bytes with length prefix.
     *
     * @param fieldNumber the field number
     * @param data the bytes to write
     * @throws IOException if an I/O error occurs
     */
    public void writeBytesField(int fieldNumber, byte[] data) throws IOException {
        if (data == null) {
            return;
        }
        writeBuffer.clear();
        putTag(writeBuffer, fieldNumber, WIRETYPE_LEN);
        putVarint(writeBuffer, data.length);
        if (data.length <= writeBuffer.remaining()) {
            // Short payload: one channel write for header and content
            writeBuffer.put(data);
            flushWriteBuffer();
        } else {
            flushWriteBuffer();
            writeToChannel(ByteBuffer.wrap(data));
        }
    }

    /**
     * Writes a string field (UTF-8 encoded).
     *
     * @param fieldNumber the field number
     * @param value the string to write
     * @throws IOException if an I/O error occurs
     */
    public void writeStringField(int fieldNumber, String value) throws IOException {
        if (value == null) {
            return;
        }
        int chars = value.length();
        if (chars <= MAX_INLINE_STRING_CHARS) {
            // Short ASCII strings are copied straight into the scratch
            // buffer: the UTF-8 length is the char count, so no byte[] or
            // wrapper is needed. Anything else falls through.
            writeBuffer.clear();
            putTag(writeBuffer, fieldNumber, WIRETYPE_LEN);
            putVarint(writeBuffer, chars);
            int i = 0;
            while (i < chars) {
                char c = value.charAt(i);
                if (c >= 0x80) {
                    break;
                }
                writeBuffer.put((byte) c);
                i++;
            }
            if (i == chars) {
                flushWriteBuffer();
                return;
            }
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeBytesField(fieldNumber, bytes);
    }

    /**
     * Writes an embedded message field.
     * The content is written using a callback that receives a nested writer.
     *
     * <p>Note: This method buffers the message content internally to compute
     * its length before writing, which is required by the protobuf wire format.
     *
     * @param fieldNumber the field number
     * @param content the message content writer
     * @throws IOException if an I/O error occurs
     */
    public void writeMessageField(int fieldNumber, MessageContent content) throws IOException {
        if (content == null) {
            return;
        }
        
        // The wire format puts the length first, so build the content in a
        // scratch buffer to learn its size. The scratch writer is reused
        // across messages; if content re-enters this writer while a message
        // is already being built, fall back to a fresh one.
        ByteBufferChannel tempChannel;
        ProtobufWriter tempWriter;
        boolean reuse = !messageInProgress;
        if (reuse) {
            if (messageWriter == null) {
                messageChannel = new ByteBufferChannel(MESSAGE_SCRATCH_INITIAL_CAPACITY);
                messageWriter = new ProtobufWriter(messageChannel);
            }
            tempChannel = messageChannel;
            tempWriter = messageWriter;
            tempChannel.reset();
            tempWriter.bytesWritten = 0;
            messageInProgress = true;
        } else {
            tempChannel = new ByteBufferChannel(MESSAGE_SCRATCH_INITIAL_CAPACITY);
            tempWriter = new ProtobufWriter(tempChannel);
        }
        try {
            content.writeTo(tempWriter);

            ByteBuffer messageData = tempChannel.toByteBuffer();
            writeBuffer.clear();
            putTag(writeBuffer, fieldNumber, WIRETYPE_LEN);
            putVarint(writeBuffer, messageData.remaining());
            flushWriteBuffer();
            writeToChannel(messageData);
        } finally {
            if (reuse) {
                messageInProgress = false;
                if (tempChannel.size() > MESSAGE_SCRATCH_MAX_RETAINED) {
                    messageChannel = null;
                    messageWriter = null;
                }
            }
        }
    }

    /**
     * Writes length prefix and copies pre-encoded message bytes.
     * Use this when the message is already serialized.
     *
     * @param fieldNumber the field number
     * @param encodedMessage the pre-encoded message bytes
     * @throws IOException if an I/O error occurs
     */
    public void writeEncodedMessageField(int fieldNumber, ByteBuffer encodedMessage) throws IOException {
        if (encodedMessage == null || !encodedMessage.hasRemaining()) {
            return;
        }
        writeBuffer.clear();
        putTag(writeBuffer, fieldNumber, WIRETYPE_LEN);
        putVarint(writeBuffer, encodedMessage.remaining());
        flushWriteBuffer();
        writeToChannel(encodedMessage);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scratch buffer encoding
    // ─────────────────────────────────────────────────────────────────────────

    private static void putTag(ByteBuffer buffer, int fieldNumber, int wireType) {
        putVarint(buffer, tagValue(fieldNumber, wireType));
    }

    /**
     * Combines field number and wire type as an unsigned 32-bit value, so
     * field numbers of 2^28 and above are not sign-extended to 10 bytes.
     */
    private static long tagValue(int fieldNumber, int wireType) {
        return ((fieldNumber << 3) | wireType) & 0xFFFFFFFFL;
    }

    private static void putVarint(ByteBuffer buffer, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                buffer.put((byte) value);
                return;
            }
            buffer.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    // The scratch buffer is little-endian (see constructor).

    private static void putFixed64(ByteBuffer buffer, long value) {
        buffer.putLong(value);
    }

    private static void putFixed32(ByteBuffer buffer, int value) {
        buffer.putInt(value);
    }

    private void flushWriteBuffer() throws IOException {
        writeBuffer.flip();
        writeToChannel(writeBuffer);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Channel writing with retry
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a buffer to the channel, retrying if the channel is non-blocking.
     *
     * @param buffer the buffer to write
     * @throws IOException if an I/O error occurs
     */
    private void writeToChannel(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            if (written > 0) {
                bytesWritten += written;
            } else if (written == 0) {
                // Non-blocking channel not ready, yield and retry
                Thread.yield();
            } else {
                // A writable channel should never report end of stream;
                // retrying would spin forever.
                throw new ClosedChannelException();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Calculates the size of a varint in bytes.
     *
     * @param value the value
     * @return the size in bytes (1-10)
     */
    public static int varintSize(long value) {
        if ((value & (0xffffffffffffffffL << 7)) == 0) {
            return 1;
        }
        if ((value & (0xffffffffffffffffL << 14)) == 0) {
            return 2;
        }
        if ((value & (0xffffffffffffffffL << 21)) == 0) {
            return 3;
        }
        if ((value & (0xffffffffffffffffL << 28)) == 0) {
            return 4;
        }
        if ((value & (0xffffffffffffffffL << 35)) == 0) {
            return 5;
        }
        if ((value & (0xffffffffffffffffL << 42)) == 0) {
            return 6;
        }
        if ((value & (0xffffffffffffffffL << 49)) == 0) {
            return 7;
        }
        if ((value & (0xffffffffffffffffL << 56)) == 0) {
            return 8;
        }
        if ((value & (0xffffffffffffffffL << 63)) == 0) {
            return 9;
        }
        return 10;
    }

    /**
     * Interface for writing embedded message content.
     */
    public interface MessageContent {
        /**
         * Writes the message content to the given writer.
         *
         * @param writer the writer to write to
         * @throws IOException if an I/O error occurs
         */
        void writeTo(ProtobufWriter writer) throws IOException;
    }
}
