/*
 * ProtobufParser.java
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.MessageFormat;
import java.util.ResourceBundle;

/**
 * Push-based protobuf parser.
 *
 * <p>This parser processes protobuf wire format data incrementally, calling
 * handler methods as fields are parsed. It uses an all-or-nothing approach
 * for each field: if there isn't enough data to parse a complete field,
 * the buffer position is left unchanged (at the start of the incomplete
 * field) and the parser enters an underflow state.
 *
 * <h2>Usage</h2>
 * <pre>
 * ProtobufHandler handler = new MyHandler();
 * ProtobufParser parser = new ProtobufParser(handler);
 *
 * while (channel.read(buffer) &gt; 0) {
 *     buffer.flip();
 *     parser.receive(buffer);
 *     buffer.compact();
 * }
 *
 * parser.close();
 * </pre>
 *
 * <h3>Buffer Management</h3>
 * <p>The caller is responsible for managing the input buffer. After
 * {@code receive()} returns, any unconsumed data (incomplete field)
 * remains in the buffer between position and limit. The caller should
 * call {@code compact()} before reading more data into the buffer.
 *
 * <h3>Untrusted Input</h3>
 * <p>A length-delimited field that is not an embedded message (bytes, a
 * string, a packed field) is delivered only once all of it has been received,
 * so the caller has to be able to buffer one whole field. The
 * single-argument constructor places no limit on the size a field may
 * declare: a peer can announce a multi-gigabyte field, and a caller that
 * grows its buffer to fit will try to allocate it.
 *
 * <p>When parsing data from a source you do not trust, use
 * {@link #ProtobufParser(ProtobufHandler, int, int)} and pass the largest
 * field you are prepared to buffer as {@code maxLengthDelimitedSize}. A field
 * declaring more is rejected as soon as its length prefix has been read,
 * before any of its payload is buffered. The same limit applies to the
 * declared length of embedded messages. Nesting is limited by
 * {@code maxMessageDepth} (by default {@value #DEFAULT_MAX_MESSAGE_DEPTH}).
 *
 * <h3>Underflow Handling</h3>
 * <p>When the parser cannot complete a field due to insufficient data,
 * it enters an underflow state. The next {@code receive()} call will
 * attempt to parse from the same position. If {@code close()} is called
 * while in underflow state, an exception is thrown.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtobufHandler
 */
public class ProtobufParser {

    /** Default maximum nested message depth for untrusted input. */
    public static final int DEFAULT_MAX_MESSAGE_DEPTH = 100;

    /**
     * No limit on declared length-delimited field sizes ({@code writeBytesField},
     * strings, embedded messages).
     */
    public static final int UNLIMITED_LENGTH_DELIMITED = Integer.MAX_VALUE;

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.protobuf.L10N");

    /** Wire type for variable-length integers. */
    private static final int WIRETYPE_VARINT = 0;

    /** Wire type for 64-bit fixed values. */
    private static final int WIRETYPE_I64 = 1;

    /** Wire type for length-delimited values. */
    private static final int WIRETYPE_LEN = 2;

    /** Wire type for 32-bit fixed values. */
    private static final int WIRETYPE_I32 = 5;

    private final ProtobufHandler handler;
    private final int maxMessageDepth;
    private final int maxLengthDelimitedSize;

    // Message nesting - at each level, the stream offset at which that
    // message ends (primitive stack). Offsets are measured against
    // the running consumed count, so completing a field is a single addition no
    // matter how deeply it is nested.
    private long[] messageEnd = new long[4];
    private int messageDepth;

    // Bytes of fully-parsed fields since construction or reset()
    private long consumed;

    // Underflow state
    private boolean underflow;

    // Set by tryReadVarint() to distinguish "not enough data yet" from a
    // successfully-decoded value of -1 (the canonical 10-byte varint
    // encoding of a legitimate negative int32/int64 field also decodes to
    // -1, so the return value alone cannot carry both meanings).
    private boolean varintUnderflow;

    /**
     * Creates a new parser with the given handler and default limits.
     *
     * <p>The default places no limit on the size of a length-delimited
     * field. For untrusted input use
     * {@link #ProtobufParser(ProtobufHandler, int, int)} instead; see
     * <em>Untrusted Input</em> in the class description.
     *
     * @param handler the handler to receive parse events
     */
    public ProtobufParser(ProtobufHandler handler) {
        this(handler, DEFAULT_MAX_MESSAGE_DEPTH, UNLIMITED_LENGTH_DELIMITED);
    }

    /**
     * Creates a new parser with the given handler and security limits.
     *
     * @param handler the handler to receive parse events
     * @param maxMessageDepth maximum nested embedded-message depth (at least 1)
     * @param maxLengthDelimitedSize maximum declared payload length for
     *     length-delimited fields (bytes, strings, embedded messages)
     */
    public ProtobufParser(ProtobufHandler handler, int maxMessageDepth,
            int maxLengthDelimitedSize) {
        if (handler == null) {
            throw new IllegalArgumentException("handler");
        }
        if (maxMessageDepth < 1) {
            throw new IllegalArgumentException("maxMessageDepth");
        }
        if (maxLengthDelimitedSize < 0) {
            throw new IllegalArgumentException("maxLengthDelimitedSize");
        }
        this.handler = handler;
        this.maxMessageDepth = maxMessageDepth;
        this.maxLengthDelimitedSize = maxLengthDelimitedSize;
    }

    /**
     * Returns true if the parser is in underflow state.
     *
     * <p>Underflow means the last {@code receive()} call ended with an
     * incomplete field. More data is needed before parsing can continue.
     *
     * @return true if in underflow state
     */
    public boolean isUnderflow() {
        return underflow;
    }

    /**
     * Processes protobuf data from the buffer.
     *
     * <p>Parses as many complete fields as possible, calling handler methods
     * for each. If the buffer contains an incomplete field, the parser leaves
     * the position at the start of that field and enters underflow state.
     *
     * @param data the buffer to read from (must be in read mode)
     * @throws ProtobufParseException if the data is malformed
     */
    public void receive(ByteBuffer data) throws ProtobufParseException {
        underflow = false;

        while (data.hasRemaining()) {
            // Check if we've completed any nested messages
            endCompletedMessages();

            // If no more data after closing messages, we're done
            if (!data.hasRemaining()) {
                break;
            }

            // Mark position before attempting to parse a field
            int fieldStart = data.position();

            // Try to read the tag
            int maxFieldBytes = messageBytesRemaining();
            long tagValue = tryReadVarint(data, maxFieldBytes);
            if (varintUnderflow) {
                // Underflow - reset position and return
                data.position(fieldStart);
                underflow = true;
                return;
            }

            // A tag is an unsigned 32-bit value. Truncating a wider varint
            // would let this parser read different fields from the same
            // bytes than a strict one.
            if ((tagValue >>> 32) != 0) {
                throw new ProtobufParseException(L10N.getString("err.tag_too_large"));
            }
            int tag = (int) tagValue;
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x07;

            if (fieldNumber == 0) {
                throw new ProtobufParseException(L10N.getString("err.invalid_field_number"));
            }

            int bytesConsumed;

            switch (wireType) {
                case WIRETYPE_VARINT: {
                    int maxVarintBytes = remainingFieldBytes(data, fieldStart);
                    long value = tryReadVarint(data, maxVarintBytes);
                    if (varintUnderflow) {
                        data.position(fieldStart);
                        underflow = true;
                        return;
                    }
                    bytesConsumed = data.position() - fieldStart;
                    handler.handleVarint(fieldNumber, value);
                    break;
                }

                case WIRETYPE_I64: {
                    if (!ensureFixedValueAvailable(data, fieldStart, 8)) {
                        return;
                    }
                    long value = readFixed64(data);
                    bytesConsumed = data.position() - fieldStart;
                    handler.handleFixed64(fieldNumber, value);
                    break;
                }

                case WIRETYPE_I32: {
                    if (!ensureFixedValueAvailable(data, fieldStart, 4)) {
                        return;
                    }
                    int value = readFixed32(data);
                    bytesConsumed = data.position() - fieldStart;
                    handler.handleFixed32(fieldNumber, value);
                    break;
                }

                case WIRETYPE_LEN: {
                    int maxLengthPrefixBytes = remainingFieldBytes(data, fieldStart);
                    long lengthValue = tryReadVarint(data, maxLengthPrefixBytes);
                    if (varintUnderflow) {
                        data.position(fieldStart);
                        underflow = true;
                        return;
                    }

                    // Check the full 64-bit value: casting first would turn
                    // lengths such as 2^32 + 3 into small, valid-looking ones.
                    if (lengthValue < 0) {
                        String msg = MessageFormat.format(
                                L10N.getString("err.negative_length"), lengthValue);
                        throw new ProtobufParseException(msg);
                    }
                    if (lengthValue > Integer.MAX_VALUE) {
                        String msg = MessageFormat.format(
                                L10N.getString("err.length_out_of_range"), lengthValue);
                        throw new ProtobufParseException(msg);
                    }
                    int length = (int) lengthValue;
                    if (length > maxLengthDelimitedSize) {
                        String msg = MessageFormat.format(
                                L10N.getString("err.length_delimited_too_large"), length);
                        throw new ProtobufParseException(msg);
                    }
                    ensureLengthDelimitedPayloadFits(data, fieldStart, length);

                    if (handler.isMessage(fieldNumber)) {
                        // Embedded message - count the tag + length prefix against
                        // the parent levels BEFORE pushing the new message, whose
                        // end offset is measured from the end of that prefix
                        consumed += data.position() - fieldStart;
                        bytesConsumed = 0; // Already counted, don't do it again below
                        handler.startMessage(fieldNumber);
                        pushMessageEnd(length);
                    } else {
                        // Bytes/string - need all content available
                        if (data.remaining() < length) {
                            data.position(fieldStart);
                            underflow = true;
                            return;
                        }

                        // Create a slice for the content
                        int contentStart = data.position();
                        ByteBuffer content = data.slice();
                        content.limit(length);
                        data.position(contentStart + length);

                        bytesConsumed = data.position() - fieldStart;
                        handler.handleBytes(fieldNumber, content.asReadOnlyBuffer());
                    }
                    break;
                }

                default:
                    String msg = MessageFormat.format(
                            L10N.getString("err.unknown_wire_type"), wireType);
                    throw new ProtobufParseException(msg);
            }

            // Count the field against all enclosing messages
            consumed += bytesConsumed;
        }

        // Check for any completed messages at the end
        endCompletedMessages();
    }

    /**
     * Completes parsing and validates state.
     *
     * @throws ProtobufParseException if in underflow state or unclosed messages
     */
    public void close() throws ProtobufParseException {
        if (underflow) {
            throw new ProtobufParseException(L10N.getString("err.incomplete_field"));
        }
        if (messageDepth > 0) {
            String msg = MessageFormat.format(
                    L10N.getString("err.unclosed_messages"), messageDepth);
            throw new ProtobufParseException(msg);
        }
    }

    /**
     * Resets the parser to initial state.
     *
     * <p>Call this to reuse the parser for a new independent message.
     */
    public void reset() {
        messageDepth = 0;
        consumed = 0;
        underflow = false;
    }

    // -- Private helper methods --

    /**
     * Attempts to read a varint from the buffer.
     *
     * @param data the buffer
     * @return the varint value; if {@link #varintUnderflow} is set on return,
     *      there was not enough data and the returned value must be ignored
     *      (position is left unchanged in that case)
     * @throws ProtobufParseException if the varint is malformed
     */
    private long tryReadVarint(ByteBuffer data) throws ProtobufParseException {
        return tryReadVarint(data, Integer.MAX_VALUE);
    }

    /**
     * Reads a varint using at most {@code maxBytes} from the buffer.
     *
     * @param maxBytes maximum number of value bytes (not including any bytes
     *     already consumed before this call)
     */
    private long tryReadVarint(ByteBuffer data, int maxBytes)
            throws ProtobufParseException {
        varintUnderflow = false;
        // Kept small enough to inline everywhere: most tags and small
        // values are a single byte
        if (maxBytes > 0 && data.hasRemaining()) {
            byte first = data.get();
            if (first >= 0) {
                return first;
            }
            return readVarintTail(data, maxBytes, first);
        }
        return readVarintUnavailable(data);
    }

    /**
     * Continues a varint whose first byte, already consumed, had its
     * continuation bit set.
     */
    private long readVarintTail(ByteBuffer data, int maxBytes, byte first)
            throws ProtobufParseException {
        int start = data.position() - 1;
        int avail = data.limit() - start;
        long result = first & 0x7F;

        for (int i = 1; ; i++) {
            if (i >= avail) {
                // Not enough data - reset position
                data.position(start);
                varintUnderflow = true;
                return -1;
            }
            if (i >= maxBytes) {
                throw new ProtobufParseException(
                        L10N.getString("err.field_exceeds_message"));
            }
            byte b = data.get();
            result |= (long) (b & 0x7F) << (7 * i);
            if (b >= 0) {
                return result; // Complete
            }
            if (i == 9) {
                // Tenth byte still has its continuation bit set
                throw new ProtobufParseException(L10N.getString("err.varint_too_long"));
            }
        }
    }

    /** No byte can be read: either the buffer is empty or the budget is spent. */
    private long readVarintUnavailable(ByteBuffer data) throws ProtobufParseException {
        if (data.hasRemaining()) {
            throw new ProtobufParseException(L10N.getString("err.field_exceeds_message"));
        }
        varintUnderflow = true;
        return -1;
    }

    /**
     * Bytes still available for the value portion of the field being parsed.
     */
    private int remainingFieldBytes(ByteBuffer data, int fieldStart) {
        int messageLeft = messageBytesRemaining();
        if (messageLeft == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        int fieldSoFar = data.position() - fieldStart;
        return messageLeft - fieldSoFar;
    }

    private int messageBytesRemaining() {
        if (messageDepth == 0) {
            return Integer.MAX_VALUE;
        }
        // Bounded by the declared length of the innermost message, which
        // was itself checked to fit an int when it was pushed
        return (int) (messageEnd[messageDepth - 1] - consumed);
    }

    /** Ends every open message whose last byte has been consumed. */
    private void endCompletedMessages() {
        while (messageDepth > 0 && consumed >= messageEnd[messageDepth - 1]) {
            messageDepth--;
            handler.endMessage();
        }
    }

    /**
     * Ensures a fixed-width value fits in the current nested message budget.
     *
     * @return false if the buffer does not yet hold the full value (underflow)
     */
    private boolean ensureFixedValueAvailable(ByteBuffer data, int fieldStart,
            int valueBytes) throws ProtobufParseException {
        int left = remainingFieldBytes(data, fieldStart);
        if (valueBytes > left) {
            throw new ProtobufParseException(L10N.getString("err.field_exceeds_message"));
        }
        if (data.remaining() < valueBytes) {
            data.position(fieldStart);
            underflow = true;
            return false;
        }
        return true;
    }

    private void ensureLengthDelimitedPayloadFits(ByteBuffer data, int fieldStart,
            int payloadLength) throws ProtobufParseException {
        int left = remainingFieldBytes(data, fieldStart);
        if (payloadLength > left) {
            throw new ProtobufParseException(L10N.getString("err.field_exceeds_message"));
        }
    }

    /**
     * Reads a fixed 64-bit value in little-endian order.
     * Caller must ensure 8 bytes are available.
     */
    private long readFixed64(ByteBuffer data) {
        ByteOrder prev = data.order();
        data.order(ByteOrder.LITTLE_ENDIAN);
        long result = data.getLong();
        data.order(prev);
        return result;
    }

    /**
     * Reads a fixed 32-bit value in little-endian order.
     * Caller must ensure 4 bytes are available.
     */
    private int readFixed32(ByteBuffer data) {
        ByteOrder prev = data.order();
        data.order(ByteOrder.LITTLE_ENDIAN);
        int result = data.getInt();
        data.order(prev);
        return result;
    }

    private void pushMessageEnd(int length) throws ProtobufParseException {
        if (messageDepth >= maxMessageDepth) {
            String msg = MessageFormat.format(
                    L10N.getString("err.message_depth_exceeded"), maxMessageDepth);
            throw new ProtobufParseException(msg);
        }
        if (messageDepth == messageEnd.length) {
            long[] grown = new long[messageDepth * 2];
            System.arraycopy(messageEnd, 0, grown, 0, messageDepth);
            messageEnd = grown;
        }
        messageEnd[messageDepth++] = consumed + length;
    }
}
