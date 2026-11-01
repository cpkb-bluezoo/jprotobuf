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

**[View API documentation online](https://cpkb-bluezoo.github.io/jprotobuf/doc/)**

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
- **Streaming** — a bytes or string field of any size can be passed through in
  pieces, in and out, without buffering it

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

## Streaming large fields

Normally a bytes or string field is delivered once it has been received in full.
A handler can ask for chosen fields to be streamed instead, so that a field
larger than the input buffer, or than memory, can be handled:

```java
ProtobufParser parser = new ProtobufParser(new DefaultProtobufHandler() {
    @Override
    public boolean isStreaming(int fieldNumber) {
        return fieldNumber == 3;                 // the attachment
    }

    @Override
    public void startBytes(int fieldNumber, int length) {
        // the whole length is known up front
    }

    @Override
    public void bytesContent(ByteBuffer data) {
        out.write(data);                         // consumes the piece
    }

    @Override
    public void endBytes() {
    }
});
```

A piece is a read-only view of the parser's input, not a copy. What the handler
reads by advancing the buffer's position is consumed; what it leaves comes back
at the front of the next piece, so a handler that feeds another parser can leave
that parser's unfinished token. When bytes are left the parser waits for more
data, so keep room in the input buffer for them as well as for a whole scalar
field. Fields not named by `isStreaming` still arrive through `handleBytes`.

The writer mirrors this. The length goes first on the wire, so it is declared up
front and the content must match it:

```java
writer.writeStartBytes(3, (int) Files.size(path));
while (file.read(chunk) >= 0) {
    chunk.flip();
    writer.writeBytesContent(chunk);             // writes all of it
    chunk.clear();
}
writer.writeEndBytes();
```

## Parsing untrusted input

Unless the handler streams it (see above), a bytes or string field is delivered
only once it has been received in full, so the caller must be able to buffer
one whole field. By default the parser
accepts any declared size: a peer that announces a multi-gigabyte field makes a
caller that grows its buffer to fit allocate it. For data from a source you do
not trust, set a limit on the largest field you will buffer (and, if the
default of 100 is too generous, on nesting depth):

```java
ProtobufParser parser = new ProtobufParser(
        handler,
        32,             // maxMessageDepth
        1 << 20);       // maxLengthDelimitedSize: reject fields over 1 MiB
```

A field declaring more than the limit is rejected as soon as its length prefix
has been read, before any of its payload is buffered. The limit also applies
to the declared length of embedded messages. It does not apply to a streamed
field, which the parser does not buffer; a handler that accumulates one should
check the length it is given in `startBytes`.

The `ByteBuffer` passed to `handleBytes` is a read-only view of your input
buffer, not a copy. Copy anything you need to keep after the call returns.

## Maven

Artifacts are published to [Maven Central](https://central.sonatype.com/) and
[GitHub Packages](https://github.com/cpkb-bluezoo/jprotobuf/packages) (same
coordinates; GitHub requires authentication to resolve).

```xml
<dependency>
    <groupId>org.bluezoo</groupId>
    <artifactId>jprotobuf</artifactId>
    <version>1.2.0</version>
</dependency>
```

## Build

```bash
ant build
ant test
ant perf    # optional ad-hoc throughput harness (not a CI gate)
```

Requires JDK 21+. The first `ant test` downloads the test-only dependencies
(JUnit 4 and Hamcrest) from Maven Central into `test/junit/lib/`; they are not
kept in git, and nothing is downloaded to build or use the library itself.

## License

GNU Lesser General Public License version 3 (see [LICENSE](LICENSE), which
incorporates the GNU General Public License version 3 in [LICENSE-GPL](LICENSE-GPL)).
