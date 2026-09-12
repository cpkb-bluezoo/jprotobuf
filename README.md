# jprotobuf

Protocol Buffers wire-format codec for Java.

*jprotobuf* is a zero-dependency encoder and decoder for the [Protocol Buffers
binary wire format](https://protobuf.dev/programming-guides/encoding/). It
implements the four wire types (varint, 64-bit, length-delimited, 32-bit)
without generated message classes or a runtime dependency on `protobuf-java`.

Unlike `protobuf-java`, which typically materialises an entire decoded message
as an object graph in memory, jprotobuf uses a **push parser** that delivers
one field at a time to a `ProtobufHandler`. The library keeps only fixed parser
state (nesting depth, underflow position); it does not allocate a tree of maps,
lists, or generated message objects. If your handler processes each field and
moves on — writing to a channel, updating counters, filtering — memory stays
**bounded by your I/O buffer**, not by message size.

The jar itself is tiny (~14 KB), with no transitive dependencies.

## Features

- **Pure Java** — no external dependencies
- **Tiny** — ~14 KB jar; no `protobuf-java` runtime
- **Constant-memory parsing** — event-driven; no object graph materialised by
  the library (contrast with generated `parseFrom()` / `mergeFrom()` usage)
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
