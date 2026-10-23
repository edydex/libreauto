// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.wireless;

import java.util.Arrays;

/** Independent bounded legacy WPP framing. No Bluetooth or network operations. */
public final class WppCodec {
    public static final int MAX_PAYLOAD = 4096;
    public static final int MAX_FEED = 16384;
    private WppCodec() {}

    public enum Issue { PAYLOAD_LIMIT, INPUT_LIMIT, INVALID_MESSAGE_ID, TRUNCATED_FRAME, CLOSED, REENTRANT }
    public static final class CodecException extends Exception {
        private static final long serialVersionUID = 1L;
        public final Issue issue;
        CodecException(Issue issue) { super(issue.name()); this.issue = issue; }
    }
    public interface Consumer { void accept(Frame frame) throws Exception; }

    /** Borrowed for the callback only; payload is erased when callback returns. */
    public static final class Frame implements AutoCloseable {
        public final int type;
        private byte[] payload;
        private Frame(int type, byte[] payload) { this.type = type; this.payload = payload; }
        public byte[] payloadCopy() { return raw().clone(); }
        /** An explicitly owned copy for a bounded asynchronous queue. The recipient must close it. */
        public Frame retainCopy() { return new Frame(type, raw().clone()); }
        byte[] raw() {
            if (payload == null) throw new IllegalStateException("Frame no longer available");
            return payload;
        }
        @Override public void close() { if (payload != null) { Arrays.fill(payload, (byte) 0); payload = null; } }
        @Override public String toString() { return "WppFrame(type=" + type + ",payload=REDACTED)"; }
    }

    public static byte[] encode(int type, byte[] payload) {
        if (type < 1 || type > 65535) throw new IllegalArgumentException("Invalid message ID");
        if (payload == null || payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("Payload limit");
        byte[] result = new byte[payload.length + 4];
        result[0] = (byte) (payload.length >>> 8); result[1] = (byte) payload.length;
        result[2] = (byte) (type >>> 8); result[3] = (byte) type;
        System.arraycopy(payload, 0, result, 4, payload.length);
        return result;
    }

    /** At most 4 header bytes +4096 payload bytes retained, never an output queue. */
    public static final class Decoder implements AutoCloseable {
        private final byte[] header = new byte[4];
        private int headerSize, payloadSize, expected, type;
        private byte[] payload;
        private boolean closed, feeding;
        public int bufferedBytes() { return headerSize + payloadSize; }

        public void feed(byte[] input, int offset, int length, Consumer consumer) throws Exception {
            if (closed) throw new CodecException(Issue.CLOSED);
            if (feeding) throw new CodecException(Issue.REENTRANT);
            if (input == null || offset < 0 || length < 0 || offset > input.length - length)
                throw new IllegalArgumentException("Input bounds");
            if (length > MAX_FEED) { close(); throw new CodecException(Issue.INPUT_LIMIT); }
            if (consumer == null) throw new IllegalArgumentException("Consumer required");
            feeding = true;
            try {
                int end = offset + length;
                while (offset < end) {
                    if (headerSize < 4) {
                        int n = Math.min(4 - headerSize, end - offset);
                        System.arraycopy(input, offset, header, headerSize, n); offset += n; headerSize += n;
                        if (headerSize < 4) continue;
                        expected = ((header[0] & 255) << 8) | (header[1] & 255);
                        type = ((header[2] & 255) << 8) | (header[3] & 255);
                        if (expected > MAX_PAYLOAD) throw new CodecException(Issue.PAYLOAD_LIMIT);
                        if (type == 0) throw new CodecException(Issue.INVALID_MESSAGE_ID);
                        payload = new byte[expected];
                    }
                    int n = Math.min(expected - payloadSize, end - offset);
                    System.arraycopy(input, offset, payload, payloadSize, n); offset += n; payloadSize += n;
                    if (payloadSize == expected) {
                        Frame frame = new Frame(type, payload);
                        payload = null; headerSize = payloadSize = expected = type = 0; Arrays.fill(header, (byte) 0);
                        try { consumer.accept(frame); } finally { frame.close(); }
                        if (closed) return;
                    }
                }
            } catch (Exception failure) { close(); throw failure; }
            finally { feeding = false; }
        }
        public void finish() throws CodecException {
            if (closed) throw new CodecException(Issue.CLOSED);
            boolean incomplete = headerSize != 0;
            close();
            if (incomplete) throw new CodecException(Issue.TRUNCATED_FRAME);
        }
        @Override public void close() {
            closed = true; Arrays.fill(header, (byte) 0);
            if (payload != null) Arrays.fill(payload, (byte) 0);
            payload = null; headerSize = payloadSize = expected = type = 0;
        }
        /** Only after the old coordinator has cancelled its session/callbacks. */
        public void reset() { if (feeding) throw new IllegalStateException("Feed in progress"); close(); closed = false; }
        @Override public String toString() { return "WppDecoder(buffered=" + bufferedBytes() + ",closed=" + closed + ")"; }
    }
}
