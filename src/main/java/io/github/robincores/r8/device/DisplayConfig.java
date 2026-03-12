package io.github.robincores.r8.device;

/**
 * Display timing and resolution configuration.
 *
 * @param width             internal framebuffer width (pixels)
 * @param height            internal framebuffer height (pixels)
 * @param canvasWidth       window/canvas display width (scaled)
 * @param canvasHeight      window/canvas display height (scaled)
 * @param cyclesPerScanline BUS cycles per horizontal scanline (emulated)
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

    /**
     * Total BUS cycles per frame.
     */
    public int cyclesPerFrame() {
        return cyclesPerScanline * scanlinesPerFrame;
    }
}
