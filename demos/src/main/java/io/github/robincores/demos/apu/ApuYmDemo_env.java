package io.github.robincores.r8.demos.apu;

import io.github.robincores.r8.device.APU;
import io.github.robincores.r8.device.RAM;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal YM player demo that drives the APU in YM2149-compat mode.
 *
 * <p>Supports raw YM5!/YM6! files (not LHA/LZH-compressed archives).</p>
 */
public final class ApuYmDemo_env {

    // Adjust as needed for your emulator/system clock
    private static final long CPU_HZ = 12_572_000L;

    // RAM backing (YM doesn't need it, but APU requires one for PCM subsystem)
    private static final int RAM_BYTES = 1 << 20; // 1MB

    private static final String YM_RESOURCE = "/song.ym";

    public static void main(String[] args) throws Exception {
        YmSong song = loadYmFromClasspath(YM_RESOURCE);

        System.out.printf("Loaded YM: '%s' by '%s' (%d frames @ %d Hz, clock=%d Hz)%n",
                song.title, song.author, song.frames, song.hz, song.clockHz);

        RAM ram = new RAM(RAM_BYTES);

        try (APU apu = new APU(ram, CPU_HZ, null, 0)) {

            // Enable YM compatibility engine
            apu.write(APU.REG_MODE, (byte) 0x01);

            // YM master clock (kHz)
            int khz = Math.max(1, song.clockHz / 1000);
            apu.write(APU.REG_YMCLK_KHZ_LO, (byte) (khz & 0xFF));
            apu.write(APU.REG_YMCLK_KHZ_HI, (byte) ((khz >>> 8) & 0xFF));

            // Enable A/B/C/noise + soloYM
            apu.write(APU.REG_YM_CTRL, (byte) 0x1F);

            // Master volume
            apu.write(APU.REG_MASTER_VOL, (byte) 220);

            // (Optional) headroom
            apu.write(APU.REG_MIX_GAIN, (byte) 200);

            // Stereo spread
            apu.write(APU.REG_YM_PAN_A, (byte) 40);
            apu.write(APU.REG_YM_PAN_B, (byte) 128);
            apu.write(APU.REG_YM_PAN_C, (byte) 215);
            apu.write(APU.REG_YM_PAN_NOISE, (byte) 128);

            long next = System.nanoTime();
            int hz = (song.hz > 0) ? song.hz : 50;
            int cyclesPerFrame = (int) Math.round((double) CPU_HZ / (double) hz);

            for (int i = 0; i < song.frames; i++) {
                byte[] frame = song.frameRegs[i];

                for (int r = 0; r < Math.min(frame.length, APU.YM_REG_COUNT); r++) {
                    apu.write(APU.REG_YM_BASE + r, frame[r]);
                }

                apu.tick(cyclesPerFrame);
                next = sleepUntil(next, 1_000_000_000L / hz);
            }

            Thread.sleep(250);
        }

        System.out.println("Done.");
    }

    // ---------------------------------------------------------------------
    // YM parsing (YM5/YM6)
    // ---------------------------------------------------------------------

    private static final class YmSong {
        String title;
        String author;
        int frames;
        int hz;
        int clockHz;
        byte[][] frameRegs; // [frame][16]
    }

    private static YmSong loadYmFromClasspath(String resource) throws IOException {
        try (InputStream in = ApuYmDemo_env.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("YM resource not found on classpath: " + resource);
            byte[] data = readAll(in);
            return parseYm(data);
        }
    }

    private static YmSong parseYm(byte[] data) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        // Signature: YM5! or YM6!
        String sig = readAscii4(bb);
        if (!"YM5!".equals(sig) && !"YM6!".equals(sig)) {
            throw new IOException("Unsupported YM signature: " + sig + " (need raw YM5!/YM6!, not compressed archive)");
        }

        int leon = bb.getInt();
        if (leon != 0x4C654F6E) throw new IOException("Missing 'LeOn' marker");
        int ard = bb.getInt();
        if (ard != 0x41724421) throw new IOException("Missing 'ArD!' marker");

        YmSong song = new YmSong();

        song.frames = bb.getInt();
        int songAttrs = bb.getInt();
        int digidrumCount = bb.getShort() & 0xFFFF;
        song.clockHz = bb.getInt();
        song.hz = bb.getShort() & 0xFFFF;
        bb.getInt(); // loop frame
        int extraData = bb.getShort() & 0xFFFF;

        // Skip digidrums
        for (int i = 0; i < digidrumCount; i++) {
            ensureRemaining(bb, 4);
            int size = bb.getInt();
            ensureRemaining(bb, size);
            bb.position(bb.position() + size);
        }

        // Skip extra data
        if (extraData > 0) {
            ensureRemaining(bb, extraData);
            bb.position(bb.position() + extraData);
        }

        song.title = readCString(bb);
        song.author = readCString(bb);
        readCString(bb); // comment

        song.frameRegs = new byte[song.frames][16];

        // YM attribute bit0: 1 => register-major (reg0 all frames, reg1 all frames, ...)
        boolean regMajor = (songAttrs & 0x01) != 0;

        int needed = song.frames * 16;
        ensureRemaining(bb, needed);

        if (regMajor) {
            for (int r = 0; r < 16; r++) {
                for (int f = 0; f < song.frames; f++) {
                    song.frameRegs[f][r] = bb.get();
                }
            }
        } else {
            for (int f = 0; f < song.frames; f++) {
                for (int r = 0; r < 16; r++) {
                    song.frameRegs[f][r] = bb.get();
                }
            }
        }

        return song;
    }

    private static String readAscii4(ByteBuffer bb) throws IOException {
        ensureRemaining(bb, 4);
        byte[] id = new byte[4];
        bb.get(id);
        return new String(id, StandardCharsets.US_ASCII);
    }

    private static void ensureRemaining(ByteBuffer bb, int n) throws IOException {
        if (n < 0 || bb.remaining() < n) {
            throw new IOException("Truncated YM file (need " + n + " bytes, have " + bb.remaining() + ")");
        }
    }

    private static String readCString(ByteBuffer bb) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (bb.hasRemaining()) {
            byte b = bb.get();
            if (b == 0) break;
            bos.write(b);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static long sleepUntil(long nextTime, long stepNanos) {
        nextTime += stepNanos;
        while (true) {
            long now = System.nanoTime();
            if (now >= nextTime) break;
            long delta = nextTime - now;
            if (delta > 2_000_000L) {
                try { Thread.sleep(delta / 1_000_000L - 1); }
                catch (InterruptedException ignored) {}
            }
        }
        return nextTime;
    }
}
