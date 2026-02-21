// NOTE: This file is intended as a drop-in replacement for
// src/main/java/io/github/robincores/r8/device/APU.java
// in the RobinCores repository.

package io.github.robincores.r8.device;

import io.github.robincores.r8.bus.BusDevice;
import io.github.robincores.r8.system.InterruptSink;
import io.github.robincores.r8.system.Tickable;

import javax.sound.sampled.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * R816 APU v1.2 (Hybrid chip + PCM + optional YM2149/AY compat).
 *
 * <p>Designed as <b>assembly-friendly MMIO</b>:
 * 8-bit registers, contiguous blocks, no latch protocols.
 *
 * <p>Native channels:
 * <ul>
 *   <li>2x Pulse (duty + optional sweep + simple envelope + length)</li>
 *   <li>1x Wave (32x4-bit wavetable)</li>
 *   <li>1x Noise (LFSR long/short + envelope + length)</li>
 *   <li>4x PCM voices (8-bit unsigned, addr/len/loop/rate/vol/pan + ADSR-lite)</li>
 * </ul>
 *
 * <p>Optional YM compatibility engine:
 * a 16-byte YM register file at {@link #REG_YM_BASE} which can be fed 1:1 from YM dumps.
 */
public final class APU implements BusDevice, Tickable, AutoCloseable {

    // ---------------------------------------------------------------------
    // MMIO layout (device-local offsets)
    // ---------------------------------------------------------------------
    public static final int SIZE = 0xD0;

    // Global 0x00..0x0F
    private static final int G_ENABLE       = 0x00; // bits: 0 P1,1 P2,2 WAV,3 NOI,4..7 PCM0..3
    private static final int G_MASTER_VOL   = 0x01; // 0..255
    private static final int G_MIX_MODE     = 0x02; // bit0 stereo(1)/mono(0)
    private static final int G_FRAME_CTRL   = 0x03; // bit0 frame_en, bit1 frame_irq_en
    private static final int G_FRAME_ACK    = 0x04; // write any to ack
    private static final int G_STATUS       = 0x05; // read IRQ flags etc.
    private static final int G_FILTER_CTRL  = 0x06; // bit0 LPF en, bit1 HPF en, bit2 SOFTCLIP
    private static final int G_LPF_CUTOFF   = 0x07; // 0..255
    private static final int G_HPF_CUTOFF   = 0x08; // 0..255
    private static final int G_MODE         = 0x09; // bit0 YM enable

    // v1.2 additions
    private static final int G_MIX_GAIN     = 0x0A; // 0..255, pre-gain (255=1.0)
    private static final int G_PCM_BANK0    = 0x0B; // per-voice bank (upper address bits) for >64K sample RAM
    private static final int G_PCM_BANK1    = 0x0C;
    private static final int G_PCM_BANK2    = 0x0D;
    private static final int G_PCM_BANK3    = 0x0E;
    // 0x0F reserved

    /** Public aliases so firmware/demos can use stable names. */
    public static final int REG_ENABLE      = G_ENABLE;
    public static final int REG_MASTER_VOL  = G_MASTER_VOL;
    public static final int REG_MIX_MODE    = G_MIX_MODE;
    public static final int REG_FRAME_CTRL  = G_FRAME_CTRL;
    public static final int REG_FRAME_ACK   = G_FRAME_ACK;
    public static final int REG_STATUS      = G_STATUS;
    public static final int REG_FILTER_CTRL = G_FILTER_CTRL;
    public static final int REG_LPF_CUTOFF  = G_LPF_CUTOFF;
    public static final int REG_HPF_CUTOFF  = G_HPF_CUTOFF;
    public static final int REG_MODE        = G_MODE;
    public static final int REG_MIX_GAIN    = G_MIX_GAIN;
    public static final int REG_PCM_BANK0   = G_PCM_BANK0;

    // Pulse A 0x10..0x17, Pulse B 0x18..0x1F
    private static final int P_CTRL     = 0x0; // bit0 EN, bit1 LEN_EN, bit2 ENV_EN, bit3 SWEEP_EN, bits4-5 DUTY, bit7 TRIG
    private static final int P_VOL      = 0x1; // 0..255 (envelope target)
    private static final int P_PER_LO   = 0x2;
    private static final int P_PER_HI   = 0x3;
    private static final int P_ENV      = 0x4; // hi nibble attack rate, lo nibble release rate
    private static final int P_SWEEP    = 0x5; // rate(3b) | dir(1b) | shift(3b)
    private static final int P_LENGTH   = 0x6; // 0..255 (frame ticks)
    private static final int P_PAN      = 0x7; // 0..255

    // Wave registers 0x20..0x25 + Wave RAM 0x26..0x35 (16 bytes)
    private static final int W_BASE     = 0x20;
    private static final int W_CTRL     = 0x0; // bit0 EN, bit1 LEN_EN, bits2-3 VOL_SHIFT, bit7 TRIG
    private static final int W_VOL      = 0x1;
    private static final int W_PER_LO   = 0x2;
    private static final int W_PER_HI   = 0x3;
    private static final int W_LENGTH   = 0x4;
    private static final int W_PAN      = 0x5;
    private static final int W_RAM_BASE = 0x6; // 16 bytes

    // Noise 0x36..0x3D
    private static final int N_BASE    = 0x36;
    private static final int N_CTRL    = 0x0; // bit0 EN, bit1 LEN_EN, bit2 ENV_EN, bit3 SHORT, bits4-7 RATE
    private static final int N_VOL     = 0x1;
    private static final int N_ENV     = 0x2;
    private static final int N_LENGTH  = 0x3;
    private static final int N_PAN     = 0x4;
    private static final int N_TRIG    = 0x5; // write any

    // PCM voices: 0x40..0x7F (4 blocks x 16 bytes)
    private static final int PCM0_BASE  = 0x40;
    private static final int PCM_STRIDE = 0x10;

    private static final int M_CTRL    = 0x0; // bit0 EN, bit1 LOOP, bit2 IRQ_EN, bit3 ADSR_EN, bit7 TRIG
    private static final int M_VOL     = 0x1;
    private static final int M_PAN     = 0x2;
    private static final int M_RATE    = 0x3;
    private static final int M_ADDR_LO = 0x4;
    private static final int M_ADDR_HI = 0x5;
    private static final int M_LEN_LO  = 0x6;
    private static final int M_LEN_HI  = 0x7;
    private static final int M_LOOP_LO = 0x8;
    private static final int M_LOOP_HI = 0x9;
    private static final int M_A       = 0xA;
    private static final int M_D       = 0xB;
    private static final int M_S       = 0xC;
    private static final int M_R       = 0xD;
    private static final int M_STATUS  = 0xE;
    private static final int M_ACK     = 0xF;

    // YM2149/AY compatibility region (0x80..)
    public static final int REG_YM_BASE       = 0x80;
    public static final int YM_REG_COUNT      = 16;
    public static final int REG_YMCLK_KHZ_LO  = 0x90;
    public static final int REG_YMCLK_KHZ_HI  = 0x91;
    public static final int REG_YM_PAN_A      = 0x92;
    public static final int REG_YM_PAN_B      = 0x93;
    public static final int REG_YM_PAN_C      = 0x94;
    public static final int REG_YM_PAN_NOISE  = 0x95;
    /** bits: 0 A, 1 B, 2 C, 3 noise, 4 soloYM (mute native blocks when YM enabled) */
    public static final int REG_YM_CTRL       = 0x96;

    // STATUS bits
    private static final int ST_FRAME_IRQ  = 0x01;
    private static final int ST_PCM0_IRQ   = 0x10; // ST_PCMn_IRQ = 0x10<<n

    // ---------------------------------------------------------------------
    // Audio config
    // ---------------------------------------------------------------------
    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final AudioFormat FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            SAMPLE_RATE,
            16,
            CHANNELS,
            2 * CHANNELS,
            SAMPLE_RATE,
            false
    );

    // Frame sequencer: 240 Hz (NES-like)
    private static final int FRAME_HZ = 240;

    // Internal "chip clock" for native pulse/wave math
    private static final int CHIP_HZ = 1_000_000;

    // Rate table for PCM (Hz). index 0..15
    private static final int[] PCM_RATE_TABLE = {
            4000, 6000, 8000, 11025,
            16000, 22050, 24000, 32000,
            36000, 44100, 48000, 9600,
            12000, 14000, 18000, 28000
    };

    // ---------------------------------------------------------------------
    // External connections
    // ---------------------------------------------------------------------
    private final InterruptSink sink;
    private final int irqBit;
    private final RAM ram;

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------
    private final byte[] regs = new byte[SIZE];

    private final long cpuHz;
    private final double cyclesPerSample;
    private final double cyclesPerFrameTick;

    private double sampleAcc = 0.0;
    private double frameAcc = 0.0;

    // ---------------------------------------------------------------------
    // Audio output
    // ---------------------------------------------------------------------
    private volatile SourceDataLine line;
    private final byte[] outBuf = new byte[2048];
    private int outPos = 0;
    private final AtomicBoolean audioTried = new AtomicBoolean(false);

    // ---------------------------------------------------------------------
    // Channel engines
    // ---------------------------------------------------------------------
    private final Pulse[] pulses = { new Pulse(0), new Pulse(1) };
    private final Wave wave = new Wave();
    private final Noise noise = new Noise();
    private final PcmVoice[] pcms = { new PcmVoice(0), new PcmVoice(1), new PcmVoice(2), new PcmVoice(3) };
    private final Ym2149 ym = new Ym2149();

    // Global filters state
    private float lpfL = 0, lpfR = 0;
    private float hpfL = 0, hpfR = 0;
    private float hpfPrevInL = 0, hpfPrevInR = 0;

    // Cached filter coefficients (avoid Math.exp per-sample)
    private float cachedLpfAlpha = cutoffToLpfAlpha(0);
    private float cachedHpfA = cutoffToHpfA(0);

    private int status = 0;

    public APU(RAM ram, long cpuHz, InterruptSink sink, int irqBit) {
        this.ram = ram;
        this.cpuHz = cpuHz;
        this.sink = sink;
        this.irqBit = irqBit;

        this.cyclesPerSample = (double) cpuHz / (double) SAMPLE_RATE;
        this.cyclesPerFrameTick = (double) cpuHz / (double) FRAME_HZ;

        // defaults
        regs[G_MASTER_VOL] = (byte) 0xFF;
        regs[G_MIX_MODE] = 0x01;
        regs[G_FRAME_CTRL] = 0x01;
        regs[G_MIX_GAIN] = (byte) 0xFF;

        // YM defaults
        regs[REG_YMCLK_KHZ_LO] = (byte) (2000 & 0xFF);
        regs[REG_YMCLK_KHZ_HI] = (byte) ((2000 >>> 8) & 0xFF);
        regs[REG_YM_PAN_A] = (byte) 64;
        regs[REG_YM_PAN_B] = (byte) 128;
        regs[REG_YM_PAN_C] = (byte) 192;
        regs[REG_YM_PAN_NOISE] = (byte) 128;
        regs[REG_YM_CTRL] = (byte) 0x1F;
        ym.setClockHz(2_000_000);

        // Prime cached filter coefficients from defaults
        cachedLpfAlpha = cutoffToLpfAlpha(Byte.toUnsignedInt(regs[G_LPF_CUTOFF]));
        cachedHpfA = cutoffToHpfA(Byte.toUnsignedInt(regs[G_HPF_CUTOFF]));
    }

    // ---------------------------------------------------------------------
    // BusDevice
    // ---------------------------------------------------------------------
    @Override
    public int size() { return SIZE; }

    @Override
    public synchronized byte read(int offset) {
        offset &= 0xFF;
        if (offset >= SIZE) return 0;

        if (offset == G_STATUS) return (byte) (status & 0xFF);

        if (offset >= PCM0_BASE && offset < PCM0_BASE + 4 * PCM_STRIDE) {
            int v = (offset - PCM0_BASE) / PCM_STRIDE;
            int r = (offset - PCM0_BASE) % PCM_STRIDE;
            if (r == M_STATUS) return (byte) pcms[v].statusByte();
        }

        return regs[offset];
    }

    @Override
    public synchronized void write(int offset, byte value) {
        offset &= 0xFF;
        if (offset >= SIZE) return;

        // ACK registers
        if (offset == G_FRAME_ACK) {
            status &= ~ST_FRAME_IRQ;
            return;
        }

        // PCM ACK
        if (offset >= PCM0_BASE && offset < PCM0_BASE + 4 * PCM_STRIDE) {
            int v = (offset - PCM0_BASE) / PCM_STRIDE;
            int r = (offset - PCM0_BASE) % PCM_STRIDE;
            if (r == M_ACK) {
                pcms[v].irqPending = false;
                pcms[v].ended = false;
                status &= ~(ST_PCM0_IRQ << v);
                return;
            }
        }

        // Store register
        regs[offset] = value;

        // Cache filter coefficients when registers change (avoid per-sample exp)
        if (offset == G_LPF_CUTOFF) {
            cachedLpfAlpha = cutoffToLpfAlpha(Byte.toUnsignedInt(value));
        } else if (offset == G_HPF_CUTOFF) {
            cachedHpfA = cutoffToHpfA(Byte.toUnsignedInt(value));
        }

        // YM envelope restart: every write to R13 retriggers (even if same value)
        if (offset == REG_YM_BASE + 13) {
            ym.resetEnvelope(Byte.toUnsignedInt(value) & 0x0F);
        }

        // TRIG side effects / sync
        if (offset == 0x10 + P_CTRL) syncPulseFromRegs(0, true);
        else if (offset == 0x18 + P_CTRL) syncPulseFromRegs(1, true);
        else if (offset == W_BASE + W_CTRL) syncWaveFromRegs(true);
        else if (offset == N_BASE + N_CTRL) syncNoiseFromRegs();
        else if (offset == N_BASE + N_TRIG) { syncNoiseFromRegs(); noise.trigger(); }
        else if (offset >= PCM0_BASE && offset < PCM0_BASE + 4 * PCM_STRIDE) {
            int v = (offset - PCM0_BASE) / PCM_STRIDE;
            int r = (offset - PCM0_BASE) % PCM_STRIDE;
            if (r == M_CTRL) syncPcmFromRegs(v, true);
        }

        // YM clock changes
        if (offset == REG_YMCLK_KHZ_LO || offset == REG_YMCLK_KHZ_HI) {
            int khz = Byte.toUnsignedInt(regs[REG_YMCLK_KHZ_LO]) | (Byte.toUnsignedInt(regs[REG_YMCLK_KHZ_HI]) << 8);
            if (khz <= 0) khz = 2000;
            ym.setClockHz(khz * 1000);
        }
    }

    // ---------------------------------------------------------------------
    // Tickable
    // ---------------------------------------------------------------------
    @Override
    public synchronized void tick(int cycles) {
        if (cycles <= 0) return;

        ensureAudioLine();

        sampleAcc += cycles;
        frameAcc += cycles;

        int frameCtrl = Byte.toUnsignedInt(regs[G_FRAME_CTRL]);
        if ((frameCtrl & 0x01) != 0) {
            while (frameAcc >= cyclesPerFrameTick) {
                frameAcc -= cyclesPerFrameTick;
                frameTick();
            }
        } else {
            frameAcc = 0;
        }

        while (sampleAcc >= cyclesPerSample) {
            sampleAcc -= cyclesPerSample;
            renderOneSample();
        }

        flushIfNeeded(false);
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------
    public synchronized void reset() {
        for (int i = 0; i < regs.length; i++) regs[i] = 0;
        regs[G_MASTER_VOL] = (byte) 0xFF;
        regs[G_MIX_MODE] = 0x01;
        regs[G_FRAME_CTRL] = 0x01;
        regs[G_MIX_GAIN] = (byte) 0xFF;

        // Re-prime cached filter coefficients
        cachedLpfAlpha = cutoffToLpfAlpha(Byte.toUnsignedInt(regs[G_LPF_CUTOFF]));
        cachedHpfA = cutoffToHpfA(Byte.toUnsignedInt(regs[G_HPF_CUTOFF]));

        status = 0;
        sampleAcc = 0;
        frameAcc = 0;
        outPos = 0;

        lpfL = lpfR = 0;
        hpfL = hpfR = 0;
        hpfPrevInL = hpfPrevInR = 0;

        pulses[0].reset();
        pulses[1].reset();
        wave.reset();
        noise.reset();
        for (PcmVoice v : pcms) v.reset();
        ym.reset();

        if (line != null) {
            try { line.flush(); } catch (Exception ignored) {}
        }
    }

    @Override
    public synchronized void close() {
        // Ensure the tail of audio is actually written.
        flushRemainder();
        SourceDataLine l = line;
        line = null;
        if (l != null) {
            try { l.stop(); } catch (Exception ignored) {}
            try { l.flush(); } catch (Exception ignored) {}
            try { l.close(); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------------
    // Frame tick
    // ---------------------------------------------------------------------
    private void frameTick() {
        syncPulseFromRegs(0, false);
        syncPulseFromRegs(1, false);
        syncWaveFromRegs(false);
        syncNoiseFromRegs();
        for (int i = 0; i < 4; i++) syncPcmFromRegs(i, false);

        pulses[0].frameTick();
        pulses[1].frameTick();
        wave.frameTick();
        noise.frameTick();
        for (PcmVoice p : pcms) p.frameTick();

        int frameCtrl = Byte.toUnsignedInt(regs[G_FRAME_CTRL]);
        if ((frameCtrl & 0x02) != 0) {
            status |= ST_FRAME_IRQ;
            if (sink != null) sink.raise(irqBit);
        }
    }

    // ---------------------------------------------------------------------
    // Sample render + mixer
    // ---------------------------------------------------------------------
    private void renderOneSample() {
        int enable = Byte.toUnsignedInt(regs[G_ENABLE]);

        float preGain = Byte.toUnsignedInt(regs[G_MIX_GAIN]) / 255.0f;
        float master  = Byte.toUnsignedInt(regs[G_MASTER_VOL]) / 255.0f;

        float l = 0.0f, r = 0.0f;

        boolean ymEnabled = (Byte.toUnsignedInt(regs[G_MODE]) & 0x01) != 0;
        int ymCtrl = Byte.toUnsignedInt(regs[REG_YM_CTRL]);
        boolean soloYm = ymEnabled && ((ymCtrl & 0x10) != 0);

        if (!soloYm) {
            if ((enable & 0x01) != 0) { float s = pulses[0].sampleMono(); float p = pulses[0].panNorm; l += s * (1 - p); r += s * p; }
            if ((enable & 0x02) != 0) { float s = pulses[1].sampleMono(); float p = pulses[1].panNorm; l += s * (1 - p); r += s * p; }
            if ((enable & 0x04) != 0) { float s = wave.sampleMono();      float p = wave.panNorm;       l += s * (1 - p); r += s * p; }
            if ((enable & 0x08) != 0) { float s = noise.sampleMono();     float p = noise.panNorm;      l += s * (1 - p); r += s * p; }

            for (int i = 0; i < 4; i++) {
                if ((enable & (0x10 << i)) != 0) {
                    float s = pcms[i].sampleMono();
                    float p = pcms[i].panNorm;
                    l += s * (1 - p);
                    r += s * p;
                }
            }
        }

        if (ymEnabled) {
            ym.sample();
            l += ym.outL;
            r += ym.outR;
        }

        // Headroom + master
        l *= preGain;
        r *= preGain;
        l *= master;
        r *= master;

        // Optional mono
        int mixMode = Byte.toUnsignedInt(regs[G_MIX_MODE]);
        if ((mixMode & 0x01) == 0) {
            float m = 0.5f * (l + r);
            l = m;
            r = m;
        }

        // Filters
        int fctrl = Byte.toUnsignedInt(regs[G_FILTER_CTRL]);
        if ((fctrl & 0x01) != 0) {
            float a = cachedLpfAlpha;
            lpfL += a * (l - lpfL);
            lpfR += a * (r - lpfR);
            l = lpfL;
            r = lpfR;
        }
        if ((fctrl & 0x02) != 0) {
            float a = cachedHpfA;
            float outL = a * (hpfL + l - hpfPrevInL);
            float outR = a * (hpfR + r - hpfPrevInR);
            hpfPrevInL = l;
            hpfPrevInR = r;
            hpfL = outL;
            hpfR = outR;
            l = outL;
            r = outR;
        }

        // Soft clip (cheap limiter)
        if ((fctrl & 0x04) != 0) {
            l = softClip(l);
            r = softClip(r);
        }

        // Clamp and write
        short sl = (short) (clamp(l) * 32767.0f);
        short sr = (short) (clamp(r) * 32767.0f);

        outBuf[outPos++] = (byte) (sl & 0xFF);
        outBuf[outPos++] = (byte) ((sl >>> 8) & 0xFF);
        outBuf[outPos++] = (byte) (sr & 0xFF);
        outBuf[outPos++] = (byte) ((sr >>> 8) & 0xFF);

        flushIfNeeded(true);
    }

    private static float softClip(float x) {
        float ax = Math.abs(x);
        return x / (1.0f + ax);
    }

    private static float clamp(float x) {
        if (x > 1.0f) return 1.0f;
        if (x < -1.0f) return -1.0f;
        return x;
    }

    // Filter coefficient helpers
    private static float cutoffToLpfAlpha(int reg) {
        double t = reg / 255.0;
        double hz = 80.0 + (t * t) * (18_000.0 - 80.0);
        double alpha = 1.0 - Math.exp(-2.0 * Math.PI * hz / SAMPLE_RATE);
        if (alpha < 0.00001) alpha = 0.00001;
        if (alpha > 0.99999) alpha = 0.99999;
        return (float) alpha;
    }

    private static float cutoffToHpfA(int reg) {
        double t = reg / 255.0;
        double hz = 5.0 + (t * t) * (5_000.0 - 5.0);
        double a = 1.0 / (1.0 + 2.0 * Math.PI * hz / SAMPLE_RATE);
        if (a < 0.00001) a = 0.00001;
        if (a > 0.99999) a = 0.99999;
        return (float) a;
    }

    // ---------------------------------------------------------------------
    // Audio line
    // ---------------------------------------------------------------------
    private void ensureAudioLine() {
        if (line != null) return;
        if (audioTried.getAndSet(true)) return;
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
            l.open(FORMAT, 8192);
            l.start();
            line = l;
        } catch (Exception ignored) {
            line = null;
        }
    }

    /**
     * Flush audio without risking a long block inside the emulation lock.
     *
     * <p>We only write up to {@link SourceDataLine#available()} bytes.
     * If the line is congested and we can't write, we drop this chunk (emulator-only safety).
     */
    private void flushIfNeeded(boolean duringRender) {
        if (outPos < outBuf.length) return;

        // For demos/emulator audio we prefer "always audible" over "never block".
        // Some JavaSound implementations can report 0 from available() for long periods;
        // dropping the buffer in that case produces silence.
        SourceDataLine l = line;
        if (l != null) {
            try {
                l.write(outBuf, 0, outPos); // may block briefly; keeps audio working reliably
            } catch (Exception ignored) {
                // ignore (silent)
            }
        }
        outPos = 0;
    }

    /** Flush any remaining (partial) audio buffer (used on close). */
    private void flushRemainder() {
        if (outPos <= 0) return;
        SourceDataLine l = line;
        if (l != null) {
            try {
                l.write(outBuf, 0, outPos);
            } catch (Exception ignored) {
                // ignore
            }
        }
        outPos = 0;
    }

    // ---------------------------------------------------------------------
    // Register sync
    // ---------------------------------------------------------------------
    private void syncPulseFromRegs(int idx, boolean trigWrite) {
        int base = (idx == 0) ? 0x10 : 0x18;
        Pulse p = pulses[idx];

        int ctrl = Byte.toUnsignedInt(regs[base + P_CTRL]);
        boolean newGate = (ctrl & 0x01) != 0;

        // KEY-OFF semantics: if EN is cleared while ENV is enabled, enter release instead of hard-cut.
        if (p.gate && !newGate) {
            p.keyOff();
        }
        p.gate = newGate;

        p.lenEnabled = (ctrl & 0x02) != 0;
        p.envEnabled = (ctrl & 0x04) != 0;
        p.sweepEnabled = (ctrl & 0x08) != 0;
        p.duty = (ctrl >>> 4) & 0x03;

        int vol = Byte.toUnsignedInt(regs[base + P_VOL]);
        int per = Byte.toUnsignedInt(regs[base + P_PER_LO]) | (Byte.toUnsignedInt(regs[base + P_PER_HI]) << 8);
        int env = Byte.toUnsignedInt(regs[base + P_ENV]);
        int sweep = Byte.toUnsignedInt(regs[base + P_SWEEP]);
        int len = Byte.toUnsignedInt(regs[base + P_LENGTH]);
        int pan = Byte.toUnsignedInt(regs[base + P_PAN]);

        p.targetVol = vol / 255.0f;
        p.period = Math.max(0, per);
        p.attackRate = (env >>> 4) & 0x0F;
        p.releaseRate = env & 0x0F;
        p.lengthReg = len;
        p.panNorm = pan / 255.0f;

        p.sweepRate = (sweep >>> 5) & 0x07;
        p.sweepDirDown = ((sweep >>> 4) & 0x01) != 0;
        p.sweepShift = (sweep >>> 1) & 0x07;

        if (trigWrite && (ctrl & 0x80) != 0) {
            regs[base + P_CTRL] = (byte) (ctrl & 0x7F);
            p.trigger();
        }

        // If gate is on and we were idle, consider that a "note on"
        if (p.gate && !p.active) {
            p.trigger();
        }
    }

    private void syncWaveFromRegs(boolean trigWrite) {
        int base = W_BASE;
        int ctrl = Byte.toUnsignedInt(regs[base + W_CTRL]);

        wave.gate = (ctrl & 0x01) != 0;
        wave.lenEnabled = (ctrl & 0x02) != 0;
        wave.volShift = (ctrl >>> 2) & 0x03;

        int vol = Byte.toUnsignedInt(regs[base + W_VOL]);
        int per = Byte.toUnsignedInt(regs[base + W_PER_LO]) | (Byte.toUnsignedInt(regs[base + W_PER_HI]) << 8);
        int len = Byte.toUnsignedInt(regs[base + W_LENGTH]);
        int pan = Byte.toUnsignedInt(regs[base + W_PAN]);

        wave.vol = vol / 255.0f;
        wave.period = Math.max(0, per);
        wave.lengthReg = len;
        wave.panNorm = pan / 255.0f;

        System.arraycopy(regs, base + W_RAM_BASE, wave.waveRam, 0, 16);

        if (trigWrite && (ctrl & 0x80) != 0) {
            regs[base + W_CTRL] = (byte) (ctrl & 0x7F);
            wave.trigger();
        }
        if (wave.gate && !wave.active) {
            wave.trigger();
        }
    }

    private void syncNoiseFromRegs() {
        int base = N_BASE;
        int ctrl = Byte.toUnsignedInt(regs[base + N_CTRL]);

        boolean newGate = (ctrl & 0x01) != 0;
        if (noise.gate && !newGate) {
            noise.keyOff();
        }
        noise.gate = newGate;

        noise.lenEnabled = (ctrl & 0x02) != 0;
        noise.envEnabled = (ctrl & 0x04) != 0;
        noise.shortMode = (ctrl & 0x08) != 0;
        noise.rateIndex = (ctrl >>> 4) & 0x0F;

        int vol = Byte.toUnsignedInt(regs[base + N_VOL]);
        int env = Byte.toUnsignedInt(regs[base + N_ENV]);
        int len = Byte.toUnsignedInt(regs[base + N_LENGTH]);
        int pan = Byte.toUnsignedInt(regs[base + N_PAN]);

        noise.targetVol = vol / 255.0f;
        noise.attackRate = (env >>> 4) & 0x0F;
        noise.releaseRate = env & 0x0F;
        noise.lengthReg = len;
        noise.panNorm = pan / 255.0f;

        if (noise.gate && !noise.active) {
            noise.trigger();
        }
    }

    private void syncPcmFromRegs(int idx, boolean trigWrite) {
        int base = PCM0_BASE + idx * PCM_STRIDE;
        PcmVoice v = pcms[idx];

        int ctrl = Byte.toUnsignedInt(regs[base + M_CTRL]);
        v.enabled = (ctrl & 0x01) != 0;
        v.loopEnabled = (ctrl & 0x02) != 0;
        v.irqEnabled = (ctrl & 0x04) != 0;
        v.adsrEnabled = (ctrl & 0x08) != 0;

        v.vol = Byte.toUnsignedInt(regs[base + M_VOL]) / 255.0f;
        v.panNorm = Byte.toUnsignedInt(regs[base + M_PAN]) / 255.0f;
        v.rateIndex = Byte.toUnsignedInt(regs[base + M_RATE]) & 0x0F;

        v.addr = Byte.toUnsignedInt(regs[base + M_ADDR_LO]) | (Byte.toUnsignedInt(regs[base + M_ADDR_HI]) << 8);
        v.len = Byte.toUnsignedInt(regs[base + M_LEN_LO]) | (Byte.toUnsignedInt(regs[base + M_LEN_HI]) << 8);
        v.loop = Byte.toUnsignedInt(regs[base + M_LOOP_LO]) | (Byte.toUnsignedInt(regs[base + M_LOOP_HI]) << 8);

        v.a = Byte.toUnsignedInt(regs[base + M_A]);
        v.d = Byte.toUnsignedInt(regs[base + M_D]);
        v.s = Byte.toUnsignedInt(regs[base + M_S]);
        v.r = Byte.toUnsignedInt(regs[base + M_R]);

        if (trigWrite && (ctrl & 0x80) != 0) {
            regs[base + M_CTRL] = (byte) (ctrl & 0x7F);
            v.trigger();
        }
    }

    // ---------------------------------------------------------------------
    // Channels
    // ---------------------------------------------------------------------
    private final class Pulse {
        final int index;

        boolean gate;
        boolean active;
        boolean releasing;

        boolean lenEnabled;
        boolean envEnabled;
        boolean sweepEnabled;

        int duty;
        int period;

        int lengthReg;
        int length;
        float panNorm;

        float targetVol;
        float envVol;

        int attackRate;
        int releaseRate;

        int sweepRate;
        boolean sweepDirDown;
        int sweepShift;
        int sweepCounter;

        double phase;

        Pulse(int index) {
            this.index = index;
            reset();
        }

        void reset() {
            gate = false;
            active = false;
            releasing = false;
            lenEnabled = envEnabled = sweepEnabled = false;
            duty = 2;
            period = 0;
            lengthReg = length = 0;
            panNorm = 0.5f;
            targetVol = envVol = 0.0f;
            attackRate = releaseRate = 0;
            sweepRate = sweepShift = 0;
            sweepDirDown = false;
            sweepCounter = 0;
            phase = 0.0;
        }

        void trigger() {
            active = true;
            releasing = false;
            length = lengthReg;
            envVol = envEnabled ? 0.0f : targetVol;
            phase = 0.0;
            sweepCounter = 0;
        }

        void keyOff() {
            if (!active) return;
            if (!envEnabled) {
                active = false;
                releasing = false;
                envVol = 0.0f;
            } else {
                releasing = true;
            }
        }

        void frameTick() {
            if (!active) return;

            if (lenEnabled && length > 0) {
                length--;
                if (length == 0) releasing = true;
            }

            if (envEnabled) {
                if (!releasing) {
                    float step = rateToStep(attackRate);
                    envVol += step;
                    if (envVol > targetVol) envVol = targetVol;
                } else {
                    float step = rateToStep(releaseRate);
                    envVol -= step;
                    if (envVol < 0.0f) envVol = 0.0f;
                    if (envVol <= 0.0001f) {
                        active = false;
                        releasing = false;
                    }
                }
            } else {
                envVol = gate ? targetVol : 0.0f;
                if (!gate) active = false;
            }

            if (sweepEnabled && sweepShift != 0 && sweepRate != 0) {
                sweepCounter++;
                if (sweepCounter >= sweepRate) {
                    sweepCounter = 0;
                    int delta = period >> sweepShift;
                    if (sweepDirDown) period = Math.max(1, period - delta);
                    else period = Math.min(65535, period + delta);
                }
            }
        }

        float sampleMono() {
            if (!active) return 0f;

            double freq = (double) CHIP_HZ / (period + 1.0);
            double step = freq / SAMPLE_RATE;

            phase += step;
            if (phase >= 1.0) phase -= 1.0;

            double dutyTh = switch (duty) {
                case 0 -> 0.125;
                case 1 -> 0.25;
                case 2 -> 0.5;
                default -> 0.75;
            };

            float s = (phase < dutyTh) ? 1.0f : -1.0f;
            s *= envVol;
            return s;
        }
    }

    private final class Wave {
        boolean gate;
        boolean active;
        boolean lenEnabled;

        int volShift;
        float vol;
        int period;
        int lengthReg;
        int length;
        float panNorm;

        double phase;
        final byte[] waveRam = new byte[16];

        Wave() { reset(); }

        void reset() {
            gate = false;
            active = false;
            lenEnabled = false;
            volShift = 0;
            vol = 0;
            period = 0;
            lengthReg = length = 0;
            panNorm = 0.5f;
            phase = 0;
            for (int i = 0; i < waveRam.length; i++) waveRam[i] = 0;
        }

        void trigger() {
            active = true;
            length = lengthReg;
            phase = 0.0;
        }

        void frameTick() {
            if (!active) return;
            if (!gate) { active = false; return; }
            if (lenEnabled && length > 0) {
                length--;
                if (length == 0) active = false;
            }
        }

        float sampleMono() {
            if (!active) return 0f;

            double freq = (double) CHIP_HZ / (period + 1.0);
            double step = freq / SAMPLE_RATE;

            phase += step;
            if (phase >= 1.0) phase -= 1.0;

            int idx = ((int) (phase * 32.0)) & 31;
            int b = Byte.toUnsignedInt(waveRam[idx >> 1]);
            int nibble = ((idx & 1) == 0) ? (b >>> 4) : (b & 0x0F);
            float s = (nibble / 15.0f) * 2.0f - 1.0f;

            float scale = switch (volShift) {
                case 0 -> 1.0f;
                case 1 -> 0.5f;
                case 2 -> 0.25f;
                default -> 0.0f;
            };
            return s * vol * scale;
        }
    }

    private final class Noise {
        boolean gate;
        boolean active;
        boolean releasing;

        boolean lenEnabled;
        boolean envEnabled;
        boolean shortMode;
        int rateIndex;

        float targetVol;
        float envVol;
        int attackRate;
        int releaseRate;

        int lengthReg;
        int length;
        float panNorm;

        int lfsr;
        double phase;

        Noise() { reset(); }

        void reset() {
            gate = false;
            active = false;
            releasing = false;
            lenEnabled = envEnabled = false;
            shortMode = false;
            rateIndex = 0;
            targetVol = envVol = 0;
            attackRate = releaseRate = 0;
            lengthReg = length = 0;
            panNorm = 0.5f;
            lfsr = 0x7FFF;
            phase = 0;
        }

        void trigger() {
            active = true;
            releasing = false;
            length = lengthReg;
            envVol = envEnabled ? 0.0f : targetVol;
            lfsr = 0x7FFF;
            phase = 0.0;
        }

        void keyOff() {
            if (!active) return;
            if (!envEnabled) {
                active = false;
                releasing = false;
                envVol = 0.0f;
            } else {
                releasing = true;
            }
        }

        void frameTick() {
            if (!active) return;

            if (lenEnabled && length > 0) {
                length--;
                if (length == 0) releasing = true;
            }

            if (envEnabled) {
                if (!releasing) {
                    float step = rateToStep(attackRate);
                    envVol += step;
                    if (envVol > targetVol) envVol = targetVol;
                } else {
                    float step = rateToStep(releaseRate);
                    envVol -= step;
                    if (envVol < 0.0f) envVol = 0.0f;
                    if (envVol <= 0.0001f) {
                        active = false;
                        releasing = false;
                    }
                }
            } else {
                envVol = gate ? targetVol : 0.0f;
                if (!gate) active = false;
            }
        }

        float sampleMono() {
            if (!active) return 0f;

            double freq = 120.0 + (rateIndex * rateIndex * 55.0);
            double step = freq / SAMPLE_RATE;

            phase += step;
            if (phase >= 1.0) {
                phase -= 1.0;
                int tap = shortMode ? 6 : 1;
                int bit = (lfsr ^ (lfsr >> tap)) & 1;
                lfsr = (lfsr >> 1) | (bit << 14);
            }

            float s = ((lfsr & 1) != 0) ? 1.0f : -1.0f;
            return s * envVol;
        }
    }

    private final class PcmVoice {
        final int index;

        boolean enabled;
        boolean loopEnabled;
        boolean irqEnabled;
        boolean adsrEnabled;

        float vol;
        float panNorm;
        int rateIndex;

        int addr;
        int len;
        int loop;

        int a, d, s, r;

        int pos;
        double phase;
        float env;
        int envStage;
        boolean ended;
        boolean irqPending;

        PcmVoice(int index) {
            this.index = index;
            reset();
        }

        void reset() {
            enabled = loopEnabled = irqEnabled = adsrEnabled = false;
            vol = 0;
            panNorm = 0.5f;
            rateIndex = 0;
            addr = len = loop = 0;
            a = d = s = r = 0;
            pos = 0;
            phase = 0;
            env = 1.0f;
            envStage = 0;
            ended = false;
            irqPending = false;
        }

        void trigger() {
            pos = 0;
            phase = 0.0;
            ended = false;
            irqPending = false;
            envStage = adsrEnabled ? 0 : 2;
            env = adsrEnabled ? 0.0f : 1.0f;
        }

        void frameTick() {
            if (!enabled) return;
            if (!adsrEnabled) return;

            float aStep = adsrStep(a);
            float dStep = adsrStep(d);
            float rStep = adsrStep(r);
            float sus = (s & 0xFF) / 255.0f;

            switch (envStage) {
                case 0 -> { env += aStep; if (env >= 1.0f) { env = 1.0f; envStage = 1; } }
                case 1 -> { env -= dStep; if (env <= sus) { env = sus; envStage = 2; } }
                case 2 -> { /* sustain */ }
                case 3 -> { env -= rStep; if (env <= 0.0f) env = 0.0f; }
            }

            if (ended && envStage != 3) envStage = 3;
        }

        int statusByte() {
            int st = 0;
            if (enabled) st |= 0x01;
            if (ended) st |= 0x02;
            if (irqPending) st |= 0x80;
            return st;
        }

        float sampleMono() {
            if (!enabled) return 0f;
            if (ended && (!adsrEnabled || env <= 0.0001f)) return 0f;

            int hz = PCM_RATE_TABLE[rateIndex & 0x0F];
            double step = (double) hz / (double) SAMPLE_RATE;

            phase += step;
            if (phase >= 1.0) {
                phase -= 1.0;
                advanceOnePcmByte();
            }

            int sampleU8 = currentSample();
            float s = ((sampleU8 / 255.0f) * 2.0f - 1.0f);

            s *= vol;
            if (adsrEnabled) s *= env;
            return s;
        }

        private int currentSample() {
            if (len <= 0) return 128;
            if (pos >= len) return 128;

            // NOTE: loop is a 16-bit absolute address within the same bank as addr.
            // Cross-bank loop points are not supported by this MMIO encoding.
            int offs16 = (addr + pos) & 0xFFFF;
            int bank = Byte.toUnsignedInt(regs[G_PCM_BANK0 + index]);
            int address = (bank << 16) | offs16;

            int size = ram.size();
            if (size <= 0) return 128;
            if (address >= size) address %= size;
            return Byte.toUnsignedInt(ram.read(address));
        }

        private void advanceOnePcmByte() {
            if (ended) return;

            pos++;
            if (pos >= len) {
                if (loopEnabled && len > 0) {
                    int loopOff = loop - addr;
                    if (loopOff < 0 || loopOff >= len) loopOff = 0;
                    pos = loopOff;
                } else {
                    ended = true;
                    if (irqEnabled && !irqPending) {
                        irqPending = true;
                        status |= (ST_PCM0_IRQ << index);
                        if (sink != null) sink.raise(irqBit);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // YM2149 / AY engine
    // ---------------------------------------------------------------------
    private final class Ym2149 {
        int clockHz = 2_000_000;
        float outL, outR;

        double aPhase, bPhase, cPhase;

        // 17-bit LFSR
        int lfsr = 0x1FFFF;
        double nPhase;
        int nBit;

        // Envelope (5-bit internal, mapped to 4-bit)
        boolean envHold;
        boolean envContinue;
        boolean envAttack;
        boolean envAlternate;
        boolean envHoldMode;
        int envDir;
        int envLevel; // 0..31
        double envPhase;

        final float[] volTable16 = buildYmVolTable16();

        void setClockHz(int hz) { clockHz = Math.max(100_000, hz); }

        void reset() {
            aPhase = bPhase = cPhase = 0;
            nPhase = 0;
            lfsr = 0x1FFFF;
            nBit = 1;
            envPhase = 0;
            resetEnvelope(Byte.toUnsignedInt(regs[REG_YM_BASE + 13]) & 0x0F);
        }

        void resetEnvelope(int shape) {
            envPhase = 0;
            envHold = false;

            envContinue  = (shape & 0x08) != 0;
            envAttack    = (shape & 0x04) != 0;
            envAlternate = (shape & 0x02) != 0;
            envHoldMode  = (shape & 0x01) != 0;

            envDir = envAttack ? +1 : -1;
            envLevel = envAttack ? 0 : 31;
        }

        void sample() {
            outL = 0;
            outR = 0;

            int ctrl = Byte.toUnsignedInt(regs[REG_YM_CTRL]);
            boolean enA = (ctrl & 0x01) != 0;
            boolean enB = (ctrl & 0x02) != 0;
            boolean enC = (ctrl & 0x04) != 0;
            boolean enNoiseGlobal = (ctrl & 0x08) != 0;

            int mixer = Byte.toUnsignedInt(regs[REG_YM_BASE + 7]);

            // Noise
            int nPer = Byte.toUnsignedInt(regs[REG_YM_BASE + 6]) & 0x1F;
            if (nPer == 0) nPer = 1;
            double nFreq = (double) clockHz / (16.0 * nPer);
            nPhase += nFreq / SAMPLE_RATE;
            if (nPhase >= 1.0) {
                nPhase -= 1.0;
                int fb = (lfsr ^ (lfsr >> 2)) & 1; // taps 0 and 2
                if ((lfsr & 0x1FFFF) == 0) fb = 1; // avoid lock-up
                lfsr = (lfsr >> 1) | (fb << 16);
                nBit = lfsr & 1;
            }
            int noiseBool = enNoiseGlobal ? nBit : 1;

            int env4 = envelopeLevel4();

            if (enA) mixYmChannel(0, mixer, noiseBool, env4);
            if (enB) mixYmChannel(1, mixer, noiseBool, env4);
            if (enC) mixYmChannel(2, mixer, noiseBool, env4);
        }

        private void mixYmChannel(int ch, int mixer, int noiseBool, int env4) {
            int base = REG_YM_BASE;

            int fine = Byte.toUnsignedInt(regs[base + (ch * 2)]);
            int coarse = Byte.toUnsignedInt(regs[base + (ch * 2) + 1]) & 0x0F;
            int per = fine | (coarse << 8);
            if (per == 0) per = 1;

            double freq = (double) clockHz / (16.0 * per);
            double step = freq / SAMPLE_RATE;

            double ph;
            if (ch == 0) { aPhase += step; if (aPhase >= 1) aPhase -= 1; ph = aPhase; }
            else if (ch == 1) { bPhase += step; if (bPhase >= 1) bPhase -= 1; ph = bPhase; }
            else { cPhase += step; if (cPhase >= 1) cPhase -= 1; ph = cPhase; }

            int toneBool = (ph < 0.5) ? 1 : 0;

            boolean toneEnabled  = (mixer & (1 << ch)) == 0;
            boolean noiseEnabled = (mixer & (1 << (ch + 3))) == 0;

            int toneIn  = toneEnabled ? toneBool : 1;
            int noiseIn = noiseEnabled ? noiseBool : 1;
            int mixedBool = toneIn & noiseIn;

            int volReg = Byte.toUnsignedInt(regs[base + 8 + ch]);
            boolean useEnv = (volReg & 0x10) != 0;
            int volNib = volReg & 0x0F;
            float amp = useEnv ? volTable16[env4] : volTable16[volNib];

            // bipolar for nicer mixing
            float s = (mixedBool != 0 ? 1.0f : -1.0f) * amp;

            float panCh = switch (ch) {
                case 0 -> Byte.toUnsignedInt(regs[REG_YM_PAN_A]) / 255.0f;
                case 1 -> Byte.toUnsignedInt(regs[REG_YM_PAN_B]) / 255.0f;
                default -> Byte.toUnsignedInt(regs[REG_YM_PAN_C]) / 255.0f;
            };
            float panN = Byte.toUnsignedInt(regs[REG_YM_PAN_NOISE]) / 255.0f;

            float pan;
            if (noiseEnabled && !toneEnabled) pan = panN;
            else if (noiseEnabled) pan = 0.5f * (panCh + panN);
            else pan = panCh;

            outL += s * (1 - pan);
            outR += s * pan;
        }

        private int envelopeLevel4() {
            int per = Byte.toUnsignedInt(regs[REG_YM_BASE + 11]) | (Byte.toUnsignedInt(regs[REG_YM_BASE + 12]) << 8);
            if (per == 0) per = 1;

            double envFreq = (double) clockHz / (256.0 * per);
            envPhase += envFreq / SAMPLE_RATE;

            while (envPhase >= 1.0) {
                envPhase -= 1.0;
                envelopeTick();
            }

            return (envLevel >> 1) & 0x0F;
        }

        private void envelopeTick() {
            if (envHold) return;

            envLevel += envDir;
            boolean top = false;
            boolean bot = false;

            if (envLevel >= 31) { envLevel = 31; top = true; }
            else if (envLevel <= 0) { envLevel = 0; bot = true; }

            if (!(top || bot)) return;

            // FIX 1: CONTINUE=0 => after one sweep, envelope holds at 0 (silence), regardless of attack.
            if (!envContinue) {
                envHold = true;
                envLevel = 0;
                return;
            }

            // FIX 2: HOLD + ALTERNATE => hold at the opposite boundary.
            if (envHoldMode) {
                if (envAlternate) {
                    envLevel = (envLevel == 0) ? 31 : 0;
                }
                envHold = true;
                return;
            }

            if (envAlternate) {
                envDir = -envDir; // bounce
                return;
            }

            // wrap
            envLevel = top ? 0 : 31;
        }
    }

    private static float[] buildYmVolTable16() {
        // Measured YM2149 outputs (vol 15..0) into 1K load (V), from MiST core comments.
        double[] v15to0 = {
                3.27, 2.995, 2.741, 2.588, 2.452, 2.372, 2.301, 2.258,
                2.220, 2.198, 2.178, 2.166, 2.155, 2.148, 2.141, 2.132
        };
        double vMax = v15to0[0];
        double vMin = v15to0[v15to0.length - 1];

        float[] t = new float[16];
        t[0] = 0.0f;
        for (int vol = 1; vol < 16; vol++) {
            int idx = 15 - vol;
            double v = v15to0[idx];
            double a = (v - vMin) / (vMax - vMin);
            if (a < 0) a = 0;
            if (a > 1) a = 1;
            t[vol] = (float) a;
        }
        return t;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------
    private static float rateToStep(int rateNibble) {
        if (rateNibble <= 0) return 1.0f;
        return 1.0f / (rateNibble * 16.0f);
    }

    private static float adsrStep(int x) {
        float t = (x + 1) / 256.0f;
        return 0.002f + 0.08f * t * t;
    }
}
