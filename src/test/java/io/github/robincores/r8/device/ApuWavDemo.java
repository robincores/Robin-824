package io.github.robincores.r8.device;

import javax.sound.sampled.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;

/**
 * WAV -> APU PCM streaming demo.
 *
 * <p>
 * - Decodes a WAV (any JavaSound-supported encoding)
 * - Converts to 48kHz stereo, 16-bit signed little-endian
 * - Downmixes to mono 8-bit unsigned PCM for the APU
 * - Streams in chunks into RAM and retriggers PCM0/PCM1
 *
 * <p>
 * Usage:
 *   - Run with no args to play classpath resource: /wishes.wav
 *   - Or: java ... ApuWavDemo /path/to/file.wav
 */
public final class ApuWavDemo {

    // --- CPU/APU pacing ---
    private static final long CPU_HZ = 12_572_000L;

    // --- Streaming buffer (must fit in your RAM) ---
    private static final int RAM_SIZE = 0x10000; // 64K for demo
    private static final int BUF_FRAMES = 16 * 1024; // ~0.341s at 48k
    private static final int BUF_BYTES = BUF_FRAMES; // 8-bit mono bytes

    // Keep buffers away from low memory (vectors etc.)
    private static final int BUF_L_ADDR = 0x2000;
    private static final int BUF_R_ADDR = 0x6000;

    // Gentle fade at the end of each chunk to avoid clicks
    private static final int FADE_SAMPLES = 256;

    private static final String DEFAULT_RESOURCE = "/wishes.wav";

    private ApuWavDemo() {}

    public static void main(String[] args) throws Exception {
        Path wavPath = (args.length > 0) ? Path.of(args[0]) : materializeResource(DEFAULT_RESOURCE);
        System.out.println("WAV: " + wavPath);

        // Decode -> 48k stereo s16le
        Decoded decoded = decodeTo48kS16LE(wavPath);
        System.out.printf("Decoded as: %d ch, %.1f Hz -> APU PCM rateIndex=%d (%d Hz)%n",
                decoded.channels, decoded.sampleRateHz, decoded.rateIndex, decoded.rateHz);

        // APU + RAM
        RAM ram = new RAM(RAM_SIZE);
        try (APU apu = new APU(ram, CPU_HZ, null, 0)) {

            // Make sure the APU actually opens an audio line.
            apu.tick(1);
            configureAudioLineBestEffort(apu);

            // --- APU global setup ---
            write8(apu, APU.REG_MASTER_VOL, 255);
            if (hasReg("REG_MIX_GAIN")) {
                write8(apu, APU.REG_MIX_GAIN, 255);
            }
            write8(apu, APU.REG_MIX_MODE, 1); // stereo

            // Enable only the PCM blocks we use (PCM0 + PCM1)
            final int EN_PCM0 = 1 << 4;
            final int EN_PCM1 = 1 << 5;
            write8(apu, APU.REG_ENABLE, EN_PCM0 | EN_PCM1);

            // Stream and play
            byte[] raw = decoded.tmpBuffer;
            AudioInputStream ais = decoded.ais;

            int chunkNo = 0;
            long totalFramesPlayed = 0;
            while (true) {
                int framesRead = readSome(ais, raw, BUF_FRAMES);
                if (framesRead <= 0) break;

                byte[] mono = downmixS16StereoToU8Mono(raw, framesRead);
                applyFadeOut(mono, framesRead, FADE_SAMPLES);

                // Duplicate mono into left/right buffers (hard pan in APU)
                byte[] left = new byte[BUF_BYTES];
                byte[] right = new byte[BUF_BYTES];
                int copy = Math.min(framesRead, BUF_BYTES);
                System.arraycopy(mono, 0, left, 0, copy);
                System.arraycopy(mono, 0, right, 0, copy);
                // fill remainder with silence (128)
                for (int i = copy; i < BUF_BYTES; i++) {
                    left[i] = (byte) 128;
                    right[i] = (byte) 128;
                }

                ram.load(BUF_L_ADDR, left);
                ram.load(BUF_R_ADDR, right);

                // Start PCM0 (left) and PCM1 (right) for exactly framesRead frames.
                startPcmOneShot(apu, /*voice*/0, BUF_L_ADDR, framesRead, decoded.rateIndex, /*vol*/240, /*pan*/0);
                startPcmOneShot(apu, /*voice*/1, BUF_R_ADDR, framesRead, decoded.rateIndex, /*vol*/240, /*pan*/255);

                // Wait until both voices report END.
                waitChunkDone(apu, /*voice*/0);
                waitChunkDone(apu, /*voice*/1);

                chunkNo++;
                totalFramesPlayed += framesRead;

                if ((chunkNo % 20) == 0) {
                    double sec = totalFramesPlayed / (double) decoded.rateHz;
                    System.out.printf("...played %d chunks (%.1f s)%n", chunkNo, sec);
                }
            }

            // Let the line drain so you actually hear the tail.
            drainAudioLineBestEffort(apu);
        }

        System.out.println("Done.");
    }

    // ---------------------------------------------------------------------
    // APU programming helpers
    // ---------------------------------------------------------------------

    private static void startPcmOneShot(APU apu, int voice, int addr, int frames, int rateIndex, int vol, int pan) {
        int base = 0x40 + voice * 0x10;
        write8(apu, base + 0x1, vol);
        write8(apu, base + 0x2, pan);
        write8(apu, base + 0x3, rateIndex & 0x0F);
        write16(apu, base + 0x4, addr);
        write16(apu, base + 0x6, frames);
        write16(apu, base + 0x8, addr); // loop point (ignored in one-shot)

        // CTRL: EN + TRIG (NOTE: NO LOOP bit, otherwise END never happens)
        write8(apu, base + 0x0, 0x01 | 0x80);
    }

    private static void waitChunkDone(APU apu, int voice) {
        int base = 0x40 + voice * 0x10;

        long startNs = System.nanoTime();
        long emuCycles = 0;

        for (;;) {
            int st = read8(apu, base + 0xE);
            if ((st & 0x02) != 0) break; // END

            // Advance a small chunk of emulated time
            int step = 50_000;
            apu.tick(step);
            emuCycles += step;

            // Real-time pacing to preserve “fixed clock machine” behavior
            long targetNs = startNs + (emuCycles * 1_000_000_000L) / CPU_HZ;
            long sleepNs = targetNs - System.nanoTime();
            if (sleepNs > 0) {
                LockSupport.parkNanos(sleepNs);
            }
        }

        // clear END flag (optional)
        apu.write((base + 0xF) & 0xFF, (byte) 1);
    }

    private static void write8(APU apu, int off, int v) {
        apu.write(off & 0xFF, (byte) (v & 0xFF));
    }

    private static int read8(APU apu, int off) {
        return Byte.toUnsignedInt(apu.read(off & 0xFF));
    }

    private static void write16(APU apu, int offLo, int v16) {
        write8(apu, offLo, v16);
        write8(apu, offLo + 1, v16 >>> 8);
    }

    // ---------------------------------------------------------------------
    // WAV decode + convert
    // ---------------------------------------------------------------------

    private static final class Decoded {
        final AudioInputStream ais;
        final int channels;
        final float sampleRateHz;
        final int rateIndex;
        final int rateHz;
        final byte[] tmpBuffer;

        Decoded(AudioInputStream ais, int channels, float sampleRateHz, int rateIndex, int rateHz, byte[] tmpBuffer) {
            this.ais = ais;
            this.channels = channels;
            this.sampleRateHz = sampleRateHz;
            this.rateIndex = rateIndex;
            this.rateHz = rateHz;
            this.tmpBuffer = tmpBuffer;
        }
    }

    private static Decoded decodeTo48kS16LE(Path wav) throws Exception {
        AudioInputStream ais0 = AudioSystem.getAudioInputStream(wav.toFile());
        AudioFormat src = ais0.getFormat();

        AudioFormat dst = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                48_000f,
                16,
                Math.max(1, src.getChannels()),
                2 * Math.max(1, src.getChannels()),
                48_000f,
                false
        );
        AudioInputStream ais = AudioSystem.getAudioInputStream(dst, ais0);

        int channels = dst.getChannels();
        int rateHz = (int) dst.getSampleRate();
        int rateIndex = bestRateIndex(rateHz);

        byte[] buf = new byte[BUF_FRAMES * dst.getFrameSize()];
        return new Decoded(ais, channels, dst.getSampleRate(), rateIndex, rateHz, buf);
    }

    private static int bestRateIndex(int hz) {
        // Must match APU PCM_RATE_TABLE (we pick nearest)
        int[] t = {
                4000, 6000, 8000, 11025,
                16000, 22050, 24000, 32000,
                36000, 44100, 48000, 9600,
                12000, 14000, 18000, 28000
        };
        int best = 0;
        int bestErr = Integer.MAX_VALUE;
        for (int i = 0; i < t.length; i++) {
            int err = Math.abs(t[i] - hz);
            if (err < bestErr) {
                bestErr = err;
                best = i;
            }
        }
        return best;
    }

    private static int readSome(AudioInputStream ais, byte[] buf, int maxFrames) throws IOException {
        int frameSize = ais.getFormat().getFrameSize();
        int wantBytes = maxFrames * frameSize;

        int got = 0;
        while (got < wantBytes) {
            int n = ais.read(buf, got, wantBytes - got);
            if (n < 0) break;
            if (n == 0) break;
            got += n;
            // don't block forever; let us stream smaller reads
            if (got >= wantBytes / 2) break;
        }
        return got / frameSize;
    }

    private static byte[] downmixS16StereoToU8Mono(byte[] s16le, int frames) {
        // dst: 1 byte per frame
        byte[] out = new byte[frames];

        // We assume 48kHz, 16-bit, at least 2 channels because we asked for it.
        // If it's mono, we just copy channel 0.
        int channels = 2;
        int frameBytes = channels * 2;

        for (int i = 0; i < frames; i++) {
            int off = i * frameBytes;
            int sL = le16(s16le, off);
            int sR = le16(s16le, off + 2);
            int s = (sL + sR) >> 1;
            out[i] = s16ToU8(s);
        }
        return out;
    }

    private static int le16(byte[] b, int off) {
        int lo = b[off] & 0xFF;
        int hi = b[off + 1]; // signed
        return (hi << 8) | lo;
    }

    private static byte s16ToU8(int s) {
        int v = (s + 32768) >> 8; // 0..255
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        return (byte) v;
    }

    private static void applyFadeOut(byte[] u8, int frames, int fadeSamples) {
        int n = Math.min(fadeSamples, frames);
        for (int i = 0; i < n; i++) {
            int idx = frames - 1 - i;
            float k = (n - 1 - i) / (float) Math.max(1, n - 1);
            int x = Byte.toUnsignedInt(u8[idx]);
            int centered = x - 128;
            int y = 128 + Math.round(centered * k);
            if (y < 0) y = 0;
            if (y > 255) y = 255;
            u8[idx] = (byte) y;
        }
    }

    // ---------------------------------------------------------------------
    // Resource helper
    // ---------------------------------------------------------------------

    private static Path materializeResource(String resource) throws IOException {
        try (InputStream in = ApuWavDemo.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new FileNotFoundException("Resource not found: " + resource);
            }
            Path tmp = Files.createTempFile("apu_demo_", ".wav");
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    // ---------------------------------------------------------------------
    // Best-effort audio line configuration (fixes "silent but running" issues)
    // ---------------------------------------------------------------------

    private static void configureAudioLineBestEffort(APU apu) {
        SourceDataLine line = reflectLine(apu);
        if (line == null) {
            System.err.println("[APU] WARNING: audio line is null. JavaSound output is unavailable -> you will hear NOTHING.");
            return;
        }

        try {
            if (line.isControlSupported(BooleanControl.Type.MUTE)) {
                BooleanControl mute = (BooleanControl) line.getControl(BooleanControl.Type.MUTE);
                mute.setValue(false);
            }
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                // 0 dB if possible, else max
                float target = Math.min(0.0f, gain.getMaximum());
                if (target < gain.getMinimum()) target = gain.getMaximum();
                gain.setValue(target);
            }
        } catch (Exception ignored) {
        }
    }

    private static void drainAudioLineBestEffort(APU apu) {
        SourceDataLine line = reflectLine(apu);
        if (line == null) return;
        try {
            line.drain();
        } catch (Exception ignored) {
        }
    }

    private static SourceDataLine reflectLine(APU apu) {
        try {
            Field f = APU.class.getDeclaredField("line");
            f.setAccessible(true);
            Object o = f.get(apu);
            return (o instanceof SourceDataLine) ? (SourceDataLine) o : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasReg(String name) {
        try {
            APU.class.getField(name);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
