/*
 * module-info.java
 * Copyright (C) 2026 Chris Burdess
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
 * Zero-dependency Protocol Buffers wire-format encoder and decoder.
 *
 * <p>The parser uses a push model where bytes are fed via
 * {@link org.bluezoo.protobuf.ProtobufParser#receive(java.nio.ByteBuffer)}
 * and decoded fields are delivered via
 * {@link org.bluezoo.protobuf.ProtobufHandler}.
 *
 * @see org.bluezoo.protobuf.ProtobufParser
 * @see org.bluezoo.protobuf.ProtobufWriter
 */
module org.bluezoo.protobuf {
    exports org.bluezoo.protobuf;
}
