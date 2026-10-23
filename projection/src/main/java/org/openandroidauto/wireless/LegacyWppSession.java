// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.wireless;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** A single-thread-confined, legacy phone-side WPP reducer. Performs no I/O. */
public final class LegacyWppSession implements AutoCloseable {
    public static final String SERVICE_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66";
    public static final long START_TIMEOUT_MS = 10000, CREDENTIAL_TIMEOUT_MS = 10000;
    public static final long NETWORK_TIMEOUT_MS = 60000, TOTAL_TIMEOUT_MS = 90000;
    public enum State { WAIT_START, WAIT_CREDENTIALS, WAIT_NETWORK, READY, CLOSED }
    public enum Reason {
        CANCELLED, DEADLINE, CLOCK_REGRESSION, MALFORMED_PAYLOAD, INVALID_ENDPOINT,
        INVALID_CREDENTIALS, UNSUPPORTED_SECURITY, UNSUPPORTED_VERSION,
        UNSUPPORTED_MESSAGE, OUT_OF_ORDER, NETWORK_FAILED, NETWORK_LOST, TRANSPORT_CLOSED
    }
    public abstract static class Action { private Action() {} }
    /** Validated control frame (including a ping timestamp). Recipient owns frameCopy(). */
    public static final class SendFrame extends Action {
        public final int type;
        private final byte[] frame;
        private SendFrame(int type, byte[] payload) { this.type = type; frame = WppCodec.encode(type, payload); }
        public byte[] frameCopy() { return frame.clone(); }
        @Override public String toString() { return "SendFrame(type=" + type + ")"; }
    }
    /** Borrowed secrets: all copies made by the driver must be erased by that driver. */
    public static final class RequestNetwork extends Action {
        public final long token;
        public final Credentials credentials;
        public final Endpoint endpoint;
        private RequestNetwork(long token, Credentials credentials, Endpoint endpoint) {
            this.token = token; this.credentials = credentials; this.endpoint = endpoint;
        }
        @Override public String toString() { return "RequestNetwork(parameters=REDACTED)"; }
    }
    public static final class ConnectTransport extends Action {
        public final long token;
        public final Endpoint endpoint;
        private ConnectTransport(long token, Endpoint endpoint) { this.token = token; this.endpoint = endpoint; }
        @Override public String toString() { return "ConnectTransport(endpoint=REDACTED)"; }
    }
    public static final class Closed extends Action {
        public final Reason reason;
        private Closed(Reason reason) { this.reason = reason; }
        @Override public String toString() { return "Closed(reason=" + reason + ")"; }
    }
    public static final class Endpoint implements AutoCloseable {
        private byte[] ipv4;
        private final int port;
        private Endpoint(byte[] ipv4, int port) { this.ipv4 = ipv4; this.port = port; }
        public byte[] ipv4Copy() { requireLive(ipv4); return ipv4.clone(); }
        public int port() { requireLive(ipv4); return port; }
        public boolean isErased() { return ipv4 == null; }
        @Override public void close() { erase(ipv4); ipv4 = null; }
        @Override public String toString() { return "Endpoint(REDACTED)"; }
    }
    public static final class Credentials implements AutoCloseable {
        private byte[] ssid, passphrase, bssid;
        public final int securityMode = 8;
        public final int accessPointType;
        private Credentials(byte[] ssid, byte[] passphrase, byte[] bssid, int apType) {
            this.ssid = ssid; this.passphrase = passphrase; this.bssid = bssid; accessPointType = apType;
        }
        public byte[] ssidUtf8Copy() { requireLive(ssid); return ssid.clone(); }
        public byte[] passphraseAsciiCopy() { requireLive(passphrase); return passphrase.clone(); }
        public byte[] bssidCopy() { requireLive(bssid); return bssid.clone(); }
        public boolean isErased() { return ssid == null; }
        @Override public void close() { erase(ssid); erase(passphrase); erase(bssid); ssid = passphrase = bssid = null; }
        @Override public String toString() { return "Credentials(REDACTED)"; }
    }

    private static final AtomicLong NEXT_TOKEN = new AtomicLong();
    private final LongSupplier clock;
    private State state;
    private long token, lastTime, phaseDeadline, totalDeadline;
    private boolean versionNegotiated;
    private Endpoint endpoint;
    private Credentials credentials;
    public LegacyWppSession(LongSupplier monotonicMillis) {
        if (monotonicMillis == null) throw new IllegalArgumentException("Clock required");
        clock = monotonicMillis; state = State.CLOSED; restart();
    }
    public State state() { return state; }
    public long token() { return token; }
    /** Snapshot for an independent watchdog; only the owner calls the reducer itself. */
    public long deadlineMillis() { return state == State.READY || state == State.CLOSED ? Long.MAX_VALUE : Math.min(phaseDeadline, totalDeadline); }
    /** Only after the old RFCOMM stream/decoder and NetworkCallback have been cancelled. */
    public void restart() {
        if (state != State.CLOSED) throw new IllegalStateException("Cancel previous session first");
        eraseOwned();
        versionNegotiated = false;
        token = NEXT_TOKEN.incrementAndGet();
        if (token <= 0) throw new IllegalStateException("Session token exhausted");
        lastTime = clock.getAsLong();
        if (lastTime < 0) throw new IllegalArgumentException("Nonnegative monotonic clock required");
        totalDeadline = add(lastTime, TOTAL_TIMEOUT_MS);
        phaseDeadline = add(lastTime, START_TIMEOUT_MS); state = State.WAIT_START;
    }
    public List<Action> accept(WppCodec.Frame frame) {
        List<Action> expired = checkTime();
        if (!expired.isEmpty() || state == State.CLOSED) return expired;
        if (frame == null) throw new IllegalArgumentException("Frame required");
        if (frame.type == 8 || frame.type == 9) {
            try {
                validatePing(frame.raw());
                return frame.type == 8 ? Collections.singletonList(new SendFrame(9, frame.raw())) : Collections.emptyList();
            } catch (IllegalArgumentException malformed) { return terminate(Reason.MALFORMED_PAYLOAD); }
        }
        if (frame.type == 4) {
            if (state != State.WAIT_START || versionNegotiated) return terminate(Reason.OUT_OF_ORDER);
            try {
                int minor = parseLegacyVersion(frame.raw());
                versionNegotiated = true;
                return Collections.singletonList(new SendFrame(5, versionResponse(minor)));
            } catch (ParseFailure failure) { return terminate(failure.reason); }
            catch (IllegalArgumentException malformed) { return terminate(Reason.MALFORMED_PAYLOAD); }
        }
        if (frame.type == 5 || frame.type == 11) return terminate(Reason.UNSUPPORTED_VERSION);
        if (frame.type != 1 && frame.type != 3) return terminate(Reason.UNSUPPORTED_MESSAGE);
        if (frame.type == 1 && state != State.WAIT_START || frame.type == 3 && state != State.WAIT_CREDENTIALS)
            return terminate(Reason.OUT_OF_ORDER);
        try {
            if (frame.type == 1) {
                endpoint = parseEndpoint(frame.raw());
                state = State.WAIT_CREDENTIALS;
                phaseDeadline = Math.min(totalDeadline, add(lastTime, CREDENTIAL_TIMEOUT_MS));
                return Collections.singletonList(new SendFrame(2, new byte[0]));
            }
            credentials = parseCredentials(frame.raw());
            state = State.WAIT_NETWORK;
            phaseDeadline = Math.min(totalDeadline, add(lastTime, NETWORK_TIMEOUT_MS));
            return Arrays.asList(new SendFrame(7, new byte[] {0x18, 0}), new RequestNetwork(token, credentials, endpoint));
        } catch (ParseFailure failure) { return terminate(failure.reason); }
        catch (IllegalArgumentException malformed) { return terminate(Reason.MALFORMED_PAYLOAD); }
    }
    /** Call only after onAvailable AND verifying the requested Wi-Fi is the returned network. */
    public List<Action> networkAvailable(long callbackToken) {
        List<Action> expired = checkTime();
        if (!expired.isEmpty() || callbackToken != token || state != State.WAIT_NETWORK) return expired;
        if (credentials.isErased() || endpoint.isErased()) return terminate(Reason.NETWORK_FAILED);
        credentials.close(); credentials = null; state = State.READY;
        return Arrays.asList(new SendFrame(6, new byte[] {8, 0}), new ConnectTransport(token, endpoint));
    }
    public List<Action> networkFailed(long callbackToken) {
        List<Action> expired = checkTime();
        if (!expired.isEmpty() || callbackToken != token || state != State.WAIT_NETWORK) return expired;
        return terminate(Reason.NETWORK_FAILED);
    }
    /** Also valid after the transport handoff. The owner must release that Network lease. */
    public List<Action> networkLost(long callbackToken) {
        List<Action> expired = checkTime();
        if (!expired.isEmpty() || callbackToken != token || state != State.WAIT_NETWORK && state != State.READY) return expired;
        return terminate(Reason.NETWORK_LOST);
    }
    public List<Action> tick() { return checkTime(); }
    public List<Action> disconnected() { return terminate(Reason.TRANSPORT_CLOSED); }
    public List<Action> cancel() { return terminate(Reason.CANCELLED); }
    @Override public void close() { cancel(); }
    @Override public String toString() { return "LegacyWppSession(state=" + state + ",parameters=REDACTED)"; }
    private List<Action> checkTime() {
        if (state == State.CLOSED) return Collections.emptyList();
        long now = clock.getAsLong();
        if (now < lastTime) return terminate(Reason.CLOCK_REGRESSION);
        lastTime = now;
        if (state != State.READY && (now >= phaseDeadline || now >= totalDeadline)) return terminate(Reason.DEADLINE);
        return Collections.emptyList();
    }
    private List<Action> terminate(Reason reason) {
        if (state == State.CLOSED) return Collections.emptyList();
        state = State.CLOSED; eraseOwned();
        return Collections.singletonList(new Closed(reason));
    }
    private void eraseOwned() {
        if (credentials != null) credentials.close();
        if (endpoint != null) endpoint.close();
        credentials = null; endpoint = null;
    }
    private static Endpoint parseEndpoint(byte[] payload) {
        byte[] text = null, address = null;
        try {
            ProtoReader reader = new ProtoReader(payload); int seen = 0; long port = -1;
            while (reader.next()) {
                if (reader.field == 1) { if ((seen & 1) != 0) throw ProtoReader.bad(); seen |= 1; text = reader.bytes(15); }
                else if (reader.field == 2) { if ((seen & 2) != 0) throw ProtoReader.bad(); seen |= 2; port = reader.number(); }
                else reader.skip();
            }
            if (seen != 3 || port < 1 || port > 65535) throw new ParseFailure(Reason.INVALID_ENDPOINT);
            address = privateIpv4(text);
            Endpoint result = new Endpoint(address, (int) port); address = null; return result;
        } finally { erase(text); erase(address); }
    }
    private static void validatePing(byte[] payload) {
        // aa-proxy echoes the timestamp payload unchanged. No metadata or arbitrary blobs are echoed.
        if (payload.length > 11) throw ProtoReader.bad();
        ProtoReader reader = new ProtoReader(payload); boolean seen = false;
        while (reader.next()) {
            if (seen || reader.field != 1 || reader.wire != 0) throw ProtoReader.bad();
            reader.number(); seen = true;
        }
    }
    private static int parseLegacyVersion(byte[] payload) {
        ProtoReader reader = new ProtoReader(payload); int seen = 0; long major = -1, minor = -1;
        while (reader.next()) {
            if (reader.field != 1 && reader.field != 2) throw new ParseFailure(Reason.UNSUPPORTED_VERSION);
            int bit = 1 << reader.field;
            if ((seen & bit) != 0) throw ProtoReader.bad();
            seen |= bit;
            if (reader.field == 1) major = reader.number(); else minor = reader.number();
        }
        if (seen != 6) throw ProtoReader.bad();
        // The inspected phone accepts major1 independent of minor. This client implements only
        // the legacy endpoint/credentials continuation, with no optional session/channel claims.
        if (major != 1 || minor < 0 || minor > Integer.MAX_VALUE) throw new ParseFailure(Reason.UNSUPPORTED_VERSION);
        return (int) minor;
    }
    private static byte[] versionResponse(int minor) {
        byte[] bytes = new byte[10]; int offset = 0;
        bytes[offset++] = 8; bytes[offset++] = 1; bytes[offset++] = 16;
        do { int part = minor & 127; minor >>>= 7; bytes[offset++] = (byte) (minor == 0 ? part : part | 128); } while (minor != 0);
        bytes[offset++] = 32; bytes[offset++] = 0;
        return Arrays.copyOf(bytes, offset);
    }
    private static Credentials parseCredentials(byte[] payload) {
        byte[] ssid = null, key = null, macText = null, mac = null;
        try {
            ProtoReader reader = new ProtoReader(payload); int seen = 0; long security = -1, ap = -1;
            while (reader.next()) {
                int bit = reader.field <= 5 ? 1 << reader.field : 0;
                if (bit != 0 && (seen & bit) != 0) throw ProtoReader.bad();
                seen |= bit;
                switch (reader.field) {
                    case 1: ssid = reader.bytes(32); break;
                    case 2: key = reader.bytes(63); break;
                    case 3: macText = reader.bytes(17); break;
                    case 4: security = reader.number(); break;
                    case 5: ap = reader.number(); break;
                    default: reader.skip();
                }
            }
            if (seen != 62) throw new ParseFailure(Reason.INVALID_CREDENTIALS);
            if (security != 8) throw new ParseFailure(Reason.UNSUPPORTED_SECURITY);
            if (ap != 0 && ap != 1 || !validSsid(ssid) || !validPassphrase(key)) throw new ParseFailure(Reason.INVALID_CREDENTIALS);
            mac = mac(macText);
            Credentials result = new Credentials(ssid, key, mac, (int) ap); ssid = key = mac = null; return result;
        } finally { erase(ssid); erase(key); erase(macText); erase(mac); }
    }
    private static byte[] privateIpv4(byte[] text) {
        if (text == null) throw new ParseFailure(Reason.INVALID_ENDPOINT);
        byte[] address = new byte[4]; int component = 0, value = 0, digits = 0;
        boolean success = false;
        try {
            for (int i = 0; i <= text.length; i++) {
                int c = i == text.length ? '.' : text[i] & 255;
                if (c == '.') {
                    if (digits == 0 || component == 4) throw new ParseFailure(Reason.INVALID_ENDPOINT);
                    address[component++] = (byte) value; digits = value = 0;
                } else {
                    if (c < '0' || c > '9' || digits == 3 || digits > 0 && value == 0) throw new ParseFailure(Reason.INVALID_ENDPOINT);
                    value = value * 10 + c - '0'; digits++;
                    if (value > 255) throw new ParseFailure(Reason.INVALID_ENDPOINT);
                }
            }
            int first = address[0] & 255, second = address[1] & 255;
            if (component != 4 || !(first == 10 || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168))
                throw new ParseFailure(Reason.INVALID_ENDPOINT);
            success = true; return address;
        } finally { if (!success) erase(address); }
    }
    private static boolean validSsid(byte[] value) {
        if (value == null || value.length < 1 || value.length > 32) return false;
        java.nio.CharBuffer chars = null;
        try {
            chars = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value));
            while (chars.hasRemaining()) if (Character.isISOControl(chars.get())) return false;
            return true;
        } catch (CharacterCodingException invalid) { return false; }
        finally { if (chars != null && chars.hasArray()) Arrays.fill(chars.array(), '\0'); }
    }
    private static boolean validPassphrase(byte[] value) {
        if (value == null || value.length < 8 || value.length > 63) return false;
        for (byte b : value) if ((b & 255) < 32 || (b & 255) > 126) return false;
        return true;
    }
    private static byte[] mac(byte[] text) {
        if (text == null || text.length != 17) throw new ParseFailure(Reason.INVALID_CREDENTIALS);
        byte[] result = new byte[6]; boolean success = false;
        try {
            boolean nonzero = false, notBroadcast = false;
            for (int i = 0; i < 6; i++) {
                int high = hex(text[i * 3]), low = hex(text[i * 3 + 1]);
                if (high < 0 || low < 0 || i < 5 && text[i * 3 + 2] != ':') throw new ParseFailure(Reason.INVALID_CREDENTIALS);
                result[i] = (byte) ((high << 4) | low); nonzero |= result[i] != 0; notBroadcast |= result[i] != (byte) 255;
            }
            if (!nonzero || !notBroadcast || (result[0] & 1) != 0) throw new ParseFailure(Reason.INVALID_CREDENTIALS);
            success = true; return result;
        } finally { if (!success) erase(result); }
    }
    private static int hex(byte b) { int c = b & 255; return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1; }
    private static long add(long value, long increment) { return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment; }
    private static void erase(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    private static void requireLive(byte[] bytes) { if (bytes == null) throw new IllegalStateException("Parameters erased"); }
    private static final class ParseFailure extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        final Reason reason;
        ParseFailure(Reason reason) { super(reason.name()); this.reason = reason; }
    }
}
