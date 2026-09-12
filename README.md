# jprotobuf

Protocol Buffers wire-format codec for Java.

*jprotobuf* is a zero-dependency encoder and decoder for the [Protocol Buffers
binary wire format](https://protobuf.dev/programming-guides/encoding/). It
implements the four wire types (varint, 64-bit, length-delimited, 32-bit)
without generated message classes or a runtime dependency on `protobuf-java`.

## Features

- **Pure Java** — no external dependencies
- **NIO-first** — `ByteBuffer` and `WritableByteChannel`; no `InputStream` / `OutputStream`
- **Event-driven parser** — push bytes via `receive(ByteBuffer)`; receive decoded
  fields through a `ProtobufHandler`
- **Incremental** — chunk-invariant parsing with underflow recovery across
  buffer boundaries
- **Embedded messages** — nested message boundaries tracked by the parser when
  the handler identifies message fields

## Encoding

```java
ByteBufferChannel channel = new ByteBufferChannel(1024);
ProtobufWriter writer = new ProtobufWriter(channel);
writer.writeStringField(1, "hello");
writer.writeVarintField(2, 42);
ByteBuffer encoded = channel.toByteBuffer();
```

## Parsing

```java
ProtobufParser parser = new ProtobufParser(new DefaultProtobufHandler() {
    @Override
    public void handleVarint(int fieldNumber, long value) {
        // interpret according to your schema
    }
});

ByteBuffer buffer = ByteBuffer.allocate(8192);
while (channel.read(buffer) > 0) {
    buffer.flip();
    parser.receive(buffer);
    buffer.compact();
}
parser.close();
```

## Maven

```xml
<dependency>
    <groupId>org.bluezoo</groupId>
    <artifactId>jprotobuf</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Build

```bash
ant build
ant test
```

Requires JDK 21+.

## License

GNU Lesser General Public License version 2.1 (see [LICENSE](LICENSE)).
