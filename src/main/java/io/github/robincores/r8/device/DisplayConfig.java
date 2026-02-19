package io.github.robincores.r8.device;

/**
 * Display timing and resolution configuration.
 *
 * <p>The VPU is parameterized by this timing model. For the R816 v1 VPU, the
 * physical VRAM is fixed (64K planar), so VRAM sizing is not derived from
 * width/height here.</p>
 *
 * @param width             internal framebuffer width (pixels)
 * @param height            internal framebuffer height (pixels)
 * @param canvasWidth       window/canvas display width (scaled)
 * @param canvasHeight      window/canvas display height (scaled)
 * @param cyclesPerScanline CPU cycles per horizontal scanline (emulated)
 * @param scanlinesPerFrame total scanlines per frame (visible + vblank)
 * @param vblankStart       first scanline of the vblank period
 */
public record DisplayConfig(
        int width,
        int height,
        int canvasWidth,
        int canvasHeight,
        int cyclesPerScanline,
        int scanlinesPerFrame,
        int vblankStart
) {

    /** Total CPU cycles per frame. */
    public int cyclesPerFrame() {
        return cyclesPerScanline * scanlinesPerFrame;
    }
}
