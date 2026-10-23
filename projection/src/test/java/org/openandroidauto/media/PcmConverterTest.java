package org.openandroidauto.media;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PcmConverterTest {
    private static ByteBuffer pcm(short... samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) buffer.putShort(sample);
        return (ByteBuffer) buffer.flip();
    }

    private static short sample(byte[] output, int frame, int channel) {
        return ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).getShort(frame * 4 + channel * 2);
    }

    @Test public void native48kStereoIsBitExact() {
        ByteBuffer input = pcm(Short.MIN_VALUE, Short.MAX_VALUE, (short) -1, (short) 0, (short) 100, (short) -100);
        byte[] expected = input.array().clone();
        byte[] actual = new PcmConverter(48000, 2, PcmConverter.PCM_16).convert(input, true);
        assertArrayEquals(expected, actual);
        assertEquals(0, input.position());
    }

    @Test public void monoDuplicatesBothChannels() {
        byte[] output = new PcmConverter(48000, 1, PcmConverter.PCM_16)
                .convert(pcm((short) -12345, (short) 12345), true);
        assertEquals(8, output.length);
        assertEquals(-12345, sample(output, 0, 0));
        assertEquals(-12345, sample(output, 0, 1));
        assertEquals(12345, sample(output, 1, 0));
        assertEquals(12345, sample(output, 1, 1));
    }

    @Test public void unsigned8BitCenteredAtSilence() {
        byte[] output = new PcmConverter(48000, 1, PcmConverter.PCM_8)
                .convert(ByteBuffer.wrap(new byte[]{0, (byte) 128, (byte) 255}), true);
        assertEquals(-32768, sample(output, 0, 0));
        assertEquals(0, sample(output, 1, 0));
        assertEquals(32512, sample(output, 2, 0));
    }

    @Test public void floatClipsAndRejectsNonFiniteSamplesToSilence() {
        ByteBuffer input = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        input.putFloat(-2f).putFloat(2f).putFloat(0.5f).putFloat(Float.NaN)
                .putFloat(Float.POSITIVE_INFINITY).putFloat(-0.5f).flip();
        byte[] output = new PcmConverter(48000, 1, PcmConverter.PCM_FLOAT).convert(input, true);
        assertEquals(-32768, sample(output, 0, 0));
        assertEquals(32767, sample(output, 1, 0));
        assertEquals(16384, sample(output, 2, 0));
        assertEquals(0, sample(output, 3, 0));
        assertEquals(0, sample(output, 4, 0));
        assertEquals(-16384, sample(output, 5, 0));
    }

    @Test public void packed24BitSignedLittleEndian() {
        byte[] output = new PcmConverter(48000, 1, PcmConverter.PCM_24)
                .convert(ByteBuffer.wrap(new byte[]{0, 0, (byte) 0x80, (byte) 0xff, (byte) 0xff, 0x7f,
                        0, 0, 0x40}), true);
        assertEquals(-32768, sample(output, 0, 0));
        assertEquals(32767, sample(output, 1, 0));
        assertEquals(16384, sample(output, 2, 0));
    }

    @Test public void signed32BitScalesCorrectly() {
        ByteBuffer input = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        input.putInt(Integer.MIN_VALUE).putInt(Integer.MAX_VALUE).putInt(1 << 30).flip();
        byte[] output = new PcmConverter(48000, 1, PcmConverter.PCM_32).convert(input, true);
        assertEquals(-32768, sample(output, 0, 0));
        assertEquals(32767, sample(output, 1, 0));
        assertEquals(16384, sample(output, 2, 0));
    }

    @Test public void resamplingChunkBoundariesAreBitIdentical() {
        for (int rate : new int[]{8000, 16000, 22050, 44100, 48000, 96000, 192000}) {
            short[] input = new short[2051 * 2];
            for (int index = 0; index < input.length / 2; index++) {
                input[index * 2] = (short) (12000 * Math.sin(index * 0.11));
                input[index * 2 + 1] = (short) (6000 * Math.cos(index * 0.07));
            }
            byte[] whole = new PcmConverter(rate, 2, PcmConverter.PCM_16).convert(pcm(input), true);
            PcmConverter chunks = new PcmConverter(rate, 2, PcmConverter.PCM_16);
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            int offset = 0;
            while (offset < input.length) {
                int count = Math.min(input.length - offset, (offset % 31 + 1) * 2);
                byte[] output = chunks.convert(pcm(Arrays.copyOfRange(input, offset, offset + count)), false);
                joined.write(output, 0, output.length);
                offset += count;
            }
            byte[] finalBytes = chunks.convert(ByteBuffer.allocate(0), true);
            joined.write(finalBytes, 0, finalBytes.length);
            assertArrayEquals("rate " + rate, whole, joined.toByteArray());
            assertEquals("frame count at " + rate,
                    ((2051L * 48000 + rate - 1) / rate) * 4, whole.length);
        }
    }

    @Test public void dcLevelAndStereoSeparationSurviveEverySupportedRate() {
        for (int rate : new int[]{8000, 16000, 22050, 44100, 96000, 192000}) {
            short[] input = new short[2000];
            for (int index = 0; index < input.length; index += 2) {
                input[index] = 10000; input[index + 1] = -20000;
            }
            byte[] output = new PcmConverter(rate, 2, PcmConverter.PCM_16).convert(pcm(input), true);
            for (int frame = 0; frame < output.length / 4; frame++) {
                assertEquals(10000, sample(output, frame, 0));
                assertEquals(-20000, sample(output, frame, 1));
            }
        }
    }

    @Test public void downsamplingSuppressesAboveNyquistInsteadOfAliasing() {
        short[] input = new short[12000];
        for (int index = 0; index < input.length; index++)
            input[index] = (short) (20000 * Math.sin(2 * Math.PI * 30000 * index / 96000));
        byte[] output = new PcmConverter(96000, 1, PcmConverter.PCM_16).convert(pcm(input), true);
        double sum = 0;
        for (int frame = 100; frame < output.length / 4 - 100; frame++) {
            double value = sample(output, frame, 0);
            sum += value * value;
        }
        double rms = Math.sqrt(sum / (output.length / 4 - 200));
        assertTrue("aliased tone RMS " + rms, rms < 500);
    }

    @Test public void ordinarySpeechToneKeepsAmplitude() {
        short[] input = new short[16000];
        for (int index = 0; index < input.length; index++)
            input[index] = (short) (12000 * Math.sin(2 * Math.PI * 1000 * index / 16000));
        byte[] output = new PcmConverter(16000, 1, PcmConverter.PCM_16).convert(pcm(input), true);
        double sum = 0;
        for (int frame = 100; frame < output.length / 4 - 100; frame++) {
            double value = sample(output, frame, 0);
            sum += value * value;
        }
        double rms = Math.sqrt(sum / (output.length / 4 - 200));
        assertEquals(12000 / Math.sqrt(2), rms, 180);
    }

    @Test public void emptyStreamProducesNoPaddedAudio() {
        assertEquals(0, new PcmConverter(16000, 1, PcmConverter.PCM_16)
                .convert(ByteBuffer.allocate(0), true).length);
    }

    @Test(expected = IllegalArgumentException.class) public void partialFramesRejected() {
        new PcmConverter(48000, 2, PcmConverter.PCM_16).convert(ByteBuffer.wrap(new byte[]{1, 2}), true);
    }

    @Test(expected = IllegalArgumentException.class) public void multichannelNotSilentlyMixed() {
        new PcmConverter(48000, 6, PcmConverter.PCM_16);
    }

    @Test(expected = IllegalArgumentException.class) public void invalidSampleRateRejected() {
        new PcmConverter(0, 1, PcmConverter.PCM_16);
    }

    @Test(expected = IllegalArgumentException.class) public void oversizedBlocksRejected() {
        new PcmConverter(16000, 1, PcmConverter.PCM_16).convert(ByteBuffer.allocate(65537 * 2), false);
    }

    @Test(expected = IllegalStateException.class) public void finishedConverterCannotAccidentallyBeReused() {
        PcmConverter converter = new PcmConverter(16000, 1, PcmConverter.PCM_16);
        converter.convert(ByteBuffer.allocate(0), true);
        converter.convert(ByteBuffer.allocate(0), true);
    }
}
