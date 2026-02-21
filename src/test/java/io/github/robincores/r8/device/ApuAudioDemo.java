package io.github.robincores.r8.device;

import static java.lang.Math.*;

/**
 * Manual audio smoke demo for {@link APU}.
 *
 * Run this class from your IDE (NOT headless CI) to hear the APU output.
 */
public final class ApuAudioDemo {

    // Use APU’s public aliases so this file stays stable
    private static final int G_ENABLE      = APU.REG_ENABLE;
    private static final int G_MASTER_VOL  = APU.REG_MASTER_VOL;
    private static final int G_MIX_MODE    = APU.REG_MIX_MODE;
    private static final int G_FRAME_CTRL  = APU.REG_FRAME_CTRL;
    private static final int G_FILTER_CTRL = APU.REG_FILTER_CTRL;
    private static final int G_LPF_CUTOFF  = APU.REG_LPF_CUTOFF;

    // Pulse A base 0x10, Pulse B base 0x18
    private static final int P1_BASE = 0x10;
    private static final int P2_BASE = 0x18;
    private static final int P_CTRL   = 0x0;
    private static final int P_VOL    = 0x1;
    private static final int P_PER_LO = 0x2;
    private static final int P_ENV    = 0x4;
    private static final int P_LEN    = 0x6;
    private static final int P_PAN    = 0x7;

    // Wave base 0x20
    private static final int W_BASE   = 0x20;
    private static final int W_CTRL   = 0x0;
    private static final int W_VOL    = 0x1;
    private static final int W_PER_LO = 0x2;
    private static final int W_LEN    = 0x4;
    private static final int W_PAN    = 0x5;
    private static final int W_RAM    = 0x6; // 16 bytes

    // Noise base 0x36
    private static final int N_BASE   = 0x36;
    private static final int N_CTRL   = 0x0;
    private static final int N_VOL    = 0x1;
    private static final int N_ENV    = 0x2;
    private static final int N_LEN    = 0x3;
    private static final int N_PAN    = 0x4;
    private static final int N_TRIG   = 0x5;

    // PCM voices base 0x40
    private static final int PCM0_BASE = 0x40;
    private static final int PCM_STRIDE = 0x10;
    private static final int M_CTRL    = 0x0;
    private static final int M_VOL     = 0x1;
    private static final int M_PAN     = 0x2;
    private static final int M_RATE    = 0x3;
    private static final int M_ADDR_LO = 0x4;
    private static final int M_LEN_LO  = 0x6;
    private static final int M_LOOP_LO = 0x8;

    // Enable bits
    private static final int EN_P1   = 1 << 0;
    private static final int EN_P2   = 1 << 1;
    private static final int EN_WAV  = 1 << 2;
    private static final int EN_NOI  = 1 << 3;
    private static final int EN_PCM0 = 1 << 4;

    // Demo pacing
    private static final long CPU_HZ = 12_500_000L;

    public static void main(String[] args) throws Exception {
        // RAM big enough for sample addresses used here.
        RAM ram = new RAM(0x10000);

        try (APU apu = new APU(ram, CPU_HZ, null, 0)) {

            // --- Global init ---
            write8(apu, G_MASTER_VOL, 255);
            write8(apu, G_MIX_MODE, 1);      // stereo
            write8(apu, G_FRAME_CTRL, 1);    // frame sequencer on

            // Optional gentle low-pass
            write8(apu, G_FILTER_CTRL, 0x01);
            write8(apu, G_LPF_CUTOFF, 80);

            // --- Program wave RAM: simple saw-ish wave (0..15 repeating) ---
            byte[] waveRam = new byte[16];
            for (int i = 0; i < 32; i++) {
                int v = i & 0x0F;
                int b = i >> 1;
                if ((i & 1) == 0) waveRam[b] = (byte) (v << 4);
                else waveRam[b] |= (byte) (v & 0x0F);
            }
            for (int i = 0; i < 16; i++) write8(apu, W_BASE + W_RAM + i, waveRam[i] & 0xFF);

            // --- Pulse A: 440 Hz, left-ish ---
            setupPulse(apu, P1_BASE, 440.0, 210, 80, 2, 70);

            // --- Pulse B: 660 Hz, right-ish ---
            setupPulse(apu, P2_BASE, 660.0, 190, 80, 1, 185);

            // --- Wave: 110 Hz bass, center ---
            setupWave(apu, 110.0, 200, 120, 1, 128);

            // --- Noise: hi-hat with decay (LEN_EN + ENV_EN) ---
            setupNoiseHat(apu, 140, 2, 10, 18, /*rate*/10, /*short*/true, /*pan*/128);

            // --- PCM0: looping sine at 8kHz sample rate ---
            int pcmAddr = 0x2000;
            int pcmLen = 8000; // 1 second @ 8k
            byte[] pcm = new byte[pcmLen];
            double sineHz = 220.0;
            double sr = 8000.0;
            for (int i = 0; i < pcmLen; i++) {
                double t = i / sr;
                double s = sin(2.0 * PI * sineHz * t);
                int u8 = (int) round(128.0 + 70.0 * s);
                pcm[i] = (byte) (u8 & 0xFF);
            }
            loadBytes(ram, pcmAddr, pcm);
            setupPcmLoop(apu, 0, pcmAddr, pcmLen, /*rateIndex(8k)=*/2, 120, 128);

            // Enable channels (master gate)
            write8(apu, G_ENABLE, EN_P1 | EN_P2 | EN_WAV | EN_NOI | EN_PCM0);

            // Run ~6 seconds
            playForSeconds(apu, 6.0);

            // small tail
            Thread.sleep(200);
        }
    }

    private static void playForSeconds(APU apu, double seconds) {
        long totalCycles = (long) (CPU_HZ * seconds);
        int chunk = 10_000;
        long done = 0;
        while (done < totalCycles) {
            apu.tick(chunk);
            done += chunk;
        }
    }

    private static void setupPulse(APU apu, int base, double hz, int vol, int length, int duty, int pan) {
        int period = periodForHz(hz);
        write8(apu, base + P_VOL, vol);
        write16(apu, base + P_PER_LO, period);
        write8(apu, base + P_ENV, 0x00);          // steady
        write8(apu, base + P_LEN, length);
        write8(apu, base + P_PAN, pan);

        // CTRL: EN + DUTY + TRIG
        int ctrl = 0x01 | ((duty & 0x03) << 4) | 0x80;
        write8(apu, base + P_CTRL, ctrl);
    }

    private static void setupWave(APU apu, double hz, int vol, int length, int volShift, int pan) {
        int period = periodForHz(hz);
        write8(apu, W_BASE + W_VOL, vol);
        write16(apu, W_BASE + W_PER_LO, period);
        write8(apu, W_BASE + W_LEN, length);
        write8(apu, W_BASE + W_PAN, pan);

        // CTRL: EN + VOL_SHIFT + TRIG
        int ctrl = 0x01 | ((volShift & 0x03) << 2) | 0x80;
        write8(apu, W_BASE + W_CTRL, ctrl);
    }

    // Hat: enable length + env so it actually decays
    private static void setupNoiseHat(APU apu, int vol, int attack, int release, int length,
                                      int rate, boolean shortMode, int pan) {
        write8(apu, N_BASE + N_VOL, vol);
        write8(apu, N_BASE + N_ENV, ((attack & 0x0F) << 4) | (release & 0x0F));
        write8(apu, N_BASE + N_LEN, length);
        write8(apu, N_BASE + N_PAN, pan);

        // CTRL: EN + LEN_EN + ENV_EN + SHORT/LONG + RATE
        int ctrl = 0x01 | 0x02 | 0x04 | (shortMode ? 0x08 : 0x00) | ((rate & 0x0F) << 4);
        write8(apu, N_BASE + N_CTRL, ctrl);
        write8(apu, N_BASE + N_TRIG, 1);
    }

    private static void setupPcmLoop(APU apu, int voice, int addr, int len, int rateIndex, int vol, int pan) {
        int base = PCM0_BASE + voice * PCM_STRIDE;
        write8(apu, base + M_VOL, vol);
        write8(apu, base + M_PAN, pan);
        write8(apu, base + M_RATE, rateIndex & 0x0F);
        write16(apu, base + M_ADDR_LO, addr);
        write16(apu, base + M_LEN_LO, len);
        write16(apu, base + M_LOOP_LO, addr); // loop from start

        // CTRL: EN + LOOP + TRIG
        int ctrl = 0x01 | 0x02 | 0x80;
        write8(apu, base + M_CTRL, ctrl);
    }

    private static int periodForHz(double hz) {
        // Matches APU.java: freq = 1_000_000 / (period+1)
        return max(1, (int) round(1_000_000.0 / hz) - 1);
    }

    private static void loadBytes(RAM ram, int addr, byte[] data) {
        for (int i = 0; i < data.length; i++) {
            ram.write((addr + i) & 0xFFFF, data[i]);
        }
    }

    private static void write8(APU apu, int off, int v) {
        apu.write(off & 0xFF, (byte) (v & 0xFF));
    }

    private static void write16(APU apu, int offLo, int v16) {
        write8(apu, offLo, v16 & 0xFF);
        write8(apu, offLo + 1, (v16 >>> 8) & 0xFF);
    }
}