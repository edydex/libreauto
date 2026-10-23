package org.openandroidauto.media;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Streaming mono/stereo PCM to little-endian 48 kHz stereo PCM16.
 * No Android dependencies. Resampling uses a normalized 32-tap polyphase
 * low-pass windowed-sinc filter; the common 48 kHz PCM16 path is exact.
 * Input blocks and retained history are bounded. One instance belongs to one
 * contiguous decoder format/seek epoch and must not be shared across threads.
 */
public final class PcmConverter {
    public static final int PCM_16 = 2, PCM_8 = 3, PCM_FLOAT = 4,
            PCM_24 = 21, PCM_32 = 22, OUTPUT_RATE = 48000;
    private static final int HALF = 16, TAPS = HALF * 2, PHASES = 1024;
    private static final int MAX_BLOCK_FRAMES = 65536;
    private final int inputRate, channels, encoding, bytesPerSample;
    private final double[][] weights;
    private double[] left = new double[128], right = new double[128];
    private int retained;
    private long retainedStart, totalFrames, outputFrames;
    private boolean finished;

    public PcmConverter(int inputRate, int channels, int encoding) {
        if (inputRate < 8000 || inputRate > 192000)
            throw new IllegalArgumentException("Unsupported PCM sample rate: " + inputRate);
        if (channels != 1 && channels != 2)
            throw new IllegalArgumentException("Only mono/stereo PCM is supported");
        this.inputRate = inputRate;
        this.channels = channels;
        this.encoding = encoding;
        switch (encoding) {
            case PCM_8: bytesPerSample = 1; break;
            case PCM_16: bytesPerSample = 2; break;
            case PCM_24: bytesPerSample = 3; break;
            case PCM_FLOAT: case PCM_32: bytesPerSample = 4; break;
            default: throw new IllegalArgumentException("Unsupported PCM encoding: " + encoding);
        }
        weights = inputRate == OUTPUT_RATE ? null : makeWeights();
    }

    public int frameSize() { return channels * bytesPerSample; }

    public byte[] convert(ByteBuffer source, boolean endOfInput) {
        if (finished) throw new IllegalStateException("PCM stream already finished");
        ByteBuffer input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (input.remaining() % frameSize() != 0)
            throw new IllegalArgumentException("Decoder emitted an incomplete PCM frame");
        int count = input.remaining() / frameSize();
        if (count > MAX_BLOCK_FRAMES)
            throw new IllegalArgumentException("PCM decoder block exceeds bounded conversion size");
        if (inputRate == OUTPUT_RATE) {
            byte[] result = new byte[count * 4];
            for (int index = 0; index < count; index++) {
                double l = read(input), r = channels == 2 ? read(input) : l;
                put(result, index * 4, l); put(result, index * 4 + 2, r);
            }
            totalFrames += count; outputFrames += count; finished = endOfInput;
            return result;
        }
        ensure(retained + count);
        for (int index = 0; index < count; index++) {
            double l = read(input), r = channels == 2 ? read(input) : l;
            left[retained] = l; right[retained] = r; retained++;
        }
        totalFrames += count;
        long finalOutputFrames = (totalFrames * OUTPUT_RATE + inputRate - 1) / inputRate;
        int maximum = Math.toIntExact(Math.max(0, finalOutputFrames - outputFrames));
        byte[] output = new byte[Math.multiplyExact(maximum, 4)];
        int written = 0;
        while (outputFrames < finalOutputFrames) {
            double position = outputFrames * (double) inputRate / OUTPUT_RATE;
            long center = (long) Math.floor(position);
            if (!endOfInput && center + HALF >= totalFrames) break;
            int phase = Math.min(PHASES - 1, (int) ((position - center) * PHASES));
            double l = 0, r = 0;
            for (int tap = 0; tap < TAPS; tap++) {
                long frame = Math.max(0, Math.min(totalFrames - 1, center + tap - HALF + 1));
                int index = Math.toIntExact(frame - retainedStart);
                if (index < 0 || index >= retained) throw new IllegalStateException("Lost PCM filter history");
                l += left[index] * weights[phase][tap];
                r += right[index] * weights[phase][tap];
            }
            put(output, written, l); put(output, written + 2, r);
            written += 4; outputFrames++;
        }
        long keepFrom = Math.max(0, (long) Math.floor(outputFrames * (double) inputRate / OUTPUT_RATE) - HALF);
        int discard = (int) Math.min(retained, Math.max(0, keepFrom - retainedStart));
        System.arraycopy(left, discard, left, 0, retained - discard);
        System.arraycopy(right, discard, right, 0, retained - discard);
        retained -= discard; retainedStart += discard;
        finished = endOfInput;
        return written == output.length ? output : Arrays.copyOf(output, written);
    }

    private double read(ByteBuffer buffer) {
        switch (encoding) {
            case PCM_8: return ((buffer.get() & 255) - 128) * 256.0;
            case PCM_16: return buffer.getShort();
            case PCM_24:
                int packed = (buffer.get() & 255) | ((buffer.get() & 255) << 8) | (buffer.get() << 16);
                return packed / 256.0;
            case PCM_32: return buffer.getInt() / 65536.0;
            case PCM_FLOAT:
                float value = buffer.getFloat();
                if (!Float.isFinite(value)) return 0;
                return Math.max(-1.0, Math.min(1.0, value)) * 32768;
            default: throw new AssertionError();
        }
    }

    private static void put(byte[] output, int offset, double value) {
        int pcm = (int) Math.max(-32768, Math.min(32767, Math.round(value)));
        output[offset] = (byte) pcm; output[offset + 1] = (byte) (pcm >> 8);
    }

    private void ensure(int frames) {
        if (frames > MAX_BLOCK_FRAMES + TAPS + 4)
            throw new IllegalArgumentException("PCM history exceeded bound");
        if (left.length < frames) {
            int capacity = Math.min(MAX_BLOCK_FRAMES + TAPS + 4, Math.max(frames, left.length * 2));
            left = Arrays.copyOf(left, capacity); right = Arrays.copyOf(right, capacity);
        }
    }

    private double[][] makeWeights() {
        double cutoff = Math.min(1.0, OUTPUT_RATE / (double) inputRate) * 0.94;
        double[][] table = new double[PHASES][TAPS];
        for (int phase = 0; phase < PHASES; phase++) {
            double fraction = phase / (double) PHASES, sum = 0;
            for (int tap = 0; tap < TAPS; tap++) {
                double x = tap - HALF + 1 - fraction;
                double y = Math.PI * cutoff * x;
                double sinc = Math.abs(y) < 1e-12 ? 1 : Math.sin(y) / y;
                double window = 0.42 + 0.5 * Math.cos(Math.PI * x / HALF)
                        + 0.08 * Math.cos(2 * Math.PI * x / HALF);
                table[phase][tap] = Math.abs(x) <= HALF ? cutoff * sinc * window : 0;
                sum += table[phase][tap];
            }
            for (int tap = 0; tap < TAPS; tap++) table[phase][tap] /= sum;
        }
        return table;
    }
}
