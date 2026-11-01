/*
 * ProtobufHandler.java
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

/**
 * Handler interface for push-based protobuf parsing.
 *
 * <p>The parser calls these methods as it encounters fields in the protobuf
 * wire format. The handler is responsible for interpreting the values based
 * on schema knowledge.
 *
 * <h2>Wire Type to Handler Method Mapping</h2>
 * <ul>
 *   <li>VARINT (0) → {@link #handleVarint(int, long)}</li>
 *   <li>I64 (1) → {@link #handleFixed64(int, long)}</li>
 *   <li>LEN (2) → {@link #handleBytes(int, ByteBuffer)},
 *                 {@link #startBytes(int, int)}/{@link #bytesContent(ByteBuffer)}/{@link #endBytes()}
 *                 or {@link #startMessage(int)}/{@link #endMessage()}</li>
 *   <li>I32 (5) → {@link #handleFixed32(int, int)}</li>
 * </ul>
 *
 * <h2>Value Interpretation</h2>
 * <p>The handler must interpret raw wire values according to the schema:
 * <ul>
 *   <li>Varint → int32, int64, uint32, uint64, sint32 (zigzag), sint64 (zigzag), bool, enum</li>
 *   <li>Fixed64 → fixed64, sfixed64, double</li>
 *   <li>Fixed32 → fixed32, sfixed32, float</li>
 *   <li>Bytes → bytes, string (UTF-8), packed repeated fields</li>
 * </ul>
 *
 * <h3>Embedded Messages</h3>
 * <p>When the parser encounters a length-delimited field that the handler
 * identifies as an embedded message (via {@link #isMessage(int)}), it calls
 * {@link #startMessage(int)} before parsing the nested content and
 * {@link #endMessage()} after. If {@code isMessage} returns false, the
 * raw bytes are delivered via {@link #handleBytes(int, ByteBuffer)}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtobufParser
 */
public interface ProtobufHandler {

    /**
     * Called when a varint field is encountered.
     *
     * <p>The value may represent: int32, int64, uint32, uint64, bool, or enum.
     * For sint32/sint64, apply zigzag decoding: {@code (value >>> 1) ^ -(value & 1)}
     *
     * @param fieldNumber the field number
     * @param value the raw varint value (unsigned)
     */
    void handleVarint(int fieldNumber, long value);

    /**
     * Called when a fixed 64-bit field is encountered.
     *
     * <p>The value may represent: fixed64, sfixed64, or double.
     * For double, use {@code Double.longBitsToDouble(value)}.
     *
     * @param fieldNumber the field number
     * @param value the 64-bit value
     */
    void handleFixed64(int fieldNumber, long value);

    /**
     * Called when a fixed 32-bit field is encountered.
     *
     * <p>The value may represent: fixed32, sfixed32, or float.
     * For float, use {@code Float.intBitsToFloat(value)}.
     *
     * @param fieldNumber the field number
     * @param value the 32-bit value
     */
    void handleFixed32(int fieldNumber, int value);

    /**
     * Called when a length-delimited field is encountered that is not a message.
     *
     * <p>The data may represent: bytes, string (UTF-8), or packed repeated fields.
     * The buffer is positioned at the start of the data and has exactly the
     * field's length remaining.
     *
     * <p>The buffer is a view of the parser's input, not a copy. It is only
     * valid until the caller next reuses or compacts the input buffer, so a
     * handler that needs the content beyond this call must copy it.
     *
     * @param fieldNumber the field number
     * @param data the field data (read-only view)
     */
    void handleBytes(int fieldNumber, ByteBuffer data);

    /**
     * Returns true if the given field number represents an embedded message.
     *
     * <p>The parser calls this to determine whether to recursively parse a
     * length-delimited field as a message or deliver it as raw bytes.
     *
     * @param fieldNumber the field number
     * @return true if this field is an embedded message
     */
    boolean isMessage(int fieldNumber);

    /**
     * Returns true if the given length-delimited field should be streamed
     * rather than delivered whole.
     *
     * <p>A streamed field is not buffered by the parser. It is reported as
     * {@link #startBytes(int, int)}, any number of
     * {@link #bytesContent(ByteBuffer)} pieces and {@link #endBytes()}, in
     * place of {@link #handleBytes(int, ByteBuffer)}, so the caller's input
     * buffer need not be able to hold it and the field can be larger than
     * memory. Use it for large bytes or string fields.
     *
     * <p>The parser asks after {@link #isMessage(int)}, which takes
     * precedence. The default is false, so a handler that does not override
     * this receives every field through {@code handleBytes} as before.
     *
     * @param fieldNumber the field number
     * @return true if this field is to be streamed
     */
    default boolean isStreaming(int fieldNumber) {
        return false;
    }

    /**
     * Called when a streamed length-delimited field begins.
     *
     * <p>The payload follows as zero or more {@link #bytesContent(ByteBuffer)}
     * calls and then {@link #endBytes()}. An empty field has no content
     * calls.
     *
     * <p>The parser's {@code maxLengthDelimitedSize} does not apply to a
     * streamed field, since the parser holds none of it. A handler that
     * accumulates the content should check {@code length} here.
     *
     * @param fieldNumber the field number
     * @param length the whole length of the payload, in bytes
     */
    default void startBytes(int fieldNumber, int length) {
        // No-op by default
    }

    /**
     * Called with a piece of the payload of the field most recently started
     * by {@link #startBytes(int, int)}.
     *
     * <p>The piece is a read-only view of the bytes the parser was given, not
     * a copy, and is valid only for the duration of the call. Pieces are as
     * large as what the caller's buffer holds, never more than what is left
     * of the field.
     *
     * <p><b>What the handler reads, by advancing the buffer's position, is
     * consumed. What it leaves is offered again at the front of the next
     * piece.</b> A handler that copies or forwards the piece should read all
     * of it. One that needs a whole token to make progress, such as a foreign
     * parser fed from the piece, reads what that parser consumed and leaves
     * its unfinished tail. When bytes are left, the parser stops until the
     * caller supplies more data, so the caller's buffer must be able to hold
     * them together with some more. Whatever the handler leaves of the last
     * piece of the field is dropped.
     *
     * <p>The default consumes the whole piece, discarding the payload.
     *
     * @param data the piece of the payload
     */
    default void bytesContent(ByteBuffer data) {
        data.position(data.limit());
    }

    /**
     * Called when the payload of the field started by
     * {@link #startBytes(int, int)} is complete.
     */
    default void endBytes() {
        // No-op by default
    }

    /**
     * Called when parsing begins for an embedded message.
     *
     * <p>The handler should push any state needed to track the nested context.
     * All subsequent handler calls relate to fields within this message until
     * {@link #endMessage()} is called.
     *
     * @param fieldNumber the field number of the message field
     */
    void startMessage(int fieldNumber);

    /**
     * Called when parsing completes for an embedded message.
     *
     * <p>The handler should pop the nested context and return to the parent.
     */
    void endMessage();
}

