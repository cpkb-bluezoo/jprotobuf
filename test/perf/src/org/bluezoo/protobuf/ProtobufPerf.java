/*
 * ProtobufPerf.java
 * Copyright (C) 2025 Chris Burdess
 *
 * Ad-hoc throughput harness (not a regression gate). Run: ant perf
 */

package org.bluezoo.protobuf;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Simple warmed throughput benchmarks for encode and parse paths.
 */
public final class ProtobufPerf {

    private static final int WARMUP_ROUNDS = 5;

    private ProtobufPerf() {
    }

    public static void main(String[] args) throws Exception {
        benchFlat();
        benchNested();
        benchDeep();
    }

    private static void benchDeep() throws Exception {
        ByteBuffer sample = encodeDeep(100, 2000);
        int bytes = sample.remaining();

        long parseNs = timeNanos(() -> {
            try {
                parseNested(sample, 200);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        report("deep parse (200 x depth 100 x 2000 fields)", parseNs, bytes * 200L);
    }

    private static ByteBuffer encodeDeep(int depth, int leafFields) throws IOException {
        ByteBufferChannel ch = new ByteBufferChannel(65536);
        writeDeep(new ProtobufWriter(ch), depth, leafFields);
        return ch.toByteBuffer();
    }

    private static void writeDeep(ProtobufWriter w, int depth, int leafFields) throws IOException {
        if (depth == 0) {
            for (int i = 0; i < leafFields; i++) {
                w.writeVarintField(2, i);
                w.writeStringField(3, "x");
            }
            return;
        }
        w.writeMessageField(1, inner -> writeDeep(inner, depth - 1, leafFields));
    }

    private static void benchFlat() throws Exception {
        ByteBuffer sample = encodeFlat(10_000);
        int bytes = sample.remaining();

        long encodeNs = timeNanos(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    encodeFlat(10_000);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        long parseNs = timeNanos(() -> {
            try {
                parseFlat(sample, 500);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        report("flat encode (200 x 10k fields x3)", encodeNs, bytes * 200L);
        report("flat parse (500 x 10k fields x3)", parseNs, bytes * 500L);
    }

    private static void benchNested() throws Exception {
        ByteBuffer sample = encodeNested(100, 50);
        int bytes = sample.remaining();

        long parseNs = timeNanos(() -> {
            try {
                parseNested(sample, 200);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        report("nested parse (200 x 100 msgs x 50 fields)", parseNs, bytes * 200L);

        long encodeNs = timeNanos(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    encodeNested(100, 50);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        report("nested encode (200 x 100 msgs x 50 fields)", encodeNs, bytes * 200L);
    }

    private static void report(String label, long nanos, long totalBytes) {
        double seconds = nanos / 1_000_000_000.0;
        double mibPerSec = (totalBytes / (1024.0 * 1024.0)) / seconds;
        System.out.printf("%s: %.1f MiB/s (%.0f ms)%n", label, mibPerSec, seconds * 1000);
    }

    private static long timeNanos(Runnable task) {
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            task.run();
        }
        long start = System.nanoTime();
        task.run();
        return System.nanoTime() - start;
    }

    private static ByteBuffer encodeFlat(int fields) throws IOException {
        ByteBufferChannel ch = new ByteBufferChannel(4096);
        ProtobufWriter w = new ProtobufWriter(ch);
        for (int i = 0; i < fields; i++) {
            w.writeVarintField(1, i);
            w.writeStringField(2, "hello");
            w.writeFixed64Field(3, 0xDEADBEEFL);
        }
        return ch.toByteBuffer();
    }

    private static void parseFlat(ByteBuffer data, int rounds) throws Exception {
        DefaultProtobufHandler handler = new DefaultProtobufHandler() {
            @Override
            public void handleVarint(int fieldNumber, long value) {
            }

            @Override
            public void handleBytes(int fieldNumber, ByteBuffer content) {
            }

            @Override
            public void handleFixed64(int fieldNumber, long value) {
            }
        };
        ProtobufParser parser = new ProtobufParser(handler);
        for (int r = 0; r < rounds; r++) {
            data.position(0);
            parser.reset();
            parser.receive(data);
            parser.close();
        }
    }

    private static ByteBuffer encodeNested(int outerMessages, int innerFields) throws IOException {
        ByteBufferChannel ch = new ByteBufferChannel(65536);
        ProtobufWriter w = new ProtobufWriter(ch);
        for (int o = 0; o < outerMessages; o++) {
            w.writeMessageField(1, inner -> {
                for (int i = 0; i < innerFields; i++) {
                    inner.writeVarintField(2, i);
                    inner.writeStringField(3, "x");
                }
            });
        }
        return ch.toByteBuffer();
    }

    private static void parseNested(ByteBuffer data, int rounds) throws Exception {
        DefaultProtobufHandler handler = new DefaultProtobufHandler() {
            @Override
            public boolean isMessage(int fieldNumber) {
                return fieldNumber == 1;
            }

            @Override
            public void startMessage(int fieldNumber) {
            }

            @Override
            public void endMessage() {
            }

            @Override
            public void handleVarint(int fieldNumber, long value) {
            }

            @Override
            public void handleBytes(int fieldNumber, ByteBuffer content) {
            }
        };
        ProtobufParser parser = new ProtobufParser(handler);
        for (int r = 0; r < rounds; r++) {
            data.position(0);
            parser.reset();
            parser.receive(data);
            parser.close();
        }
    }
}
