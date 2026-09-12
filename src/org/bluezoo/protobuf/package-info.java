/*
 * package-info.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of jprotobuf, a Protocol Buffers codec for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/jprotobuf/
 *
 * jprotobuf is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
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

/**
 * Zero-dependency Protocol Buffers wire-format codec.
 *
 * <p>{@link org.bluezoo.protobuf.ProtobufWriter}
 * writes protobuf's four wire types (varint, 64-bit, length-delimited,
 * 32-bit) to a {@link java.nio.channels.WritableByteChannel}, typically
 * {@link org.bluezoo.protobuf.ByteBufferChannel}, an
 * auto-expanding in-memory channel. {@link
 * org.bluezoo.protobuf.ProtobufParser} is the
 * corresponding push parser, delivering decoded fields incrementally to
 * a {@link org.bluezoo.protobuf.ProtobufHandler};
 * {@link org.bluezoo.protobuf.DefaultProtobufHandler}
 * supplies value-interpretation helpers so most handlers only override
 * the field callbacks they care about.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://protobuf.dev/programming-guides/encoding/">Protobuf Encoding</a>
 */
package org.bluezoo.protobuf;
