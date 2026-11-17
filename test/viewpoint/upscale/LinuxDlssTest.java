// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package viewpoint.upscale;

import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;

/**
 * End-to-end DLSS on Linux through Viewpoint's own Upscaling class: the add-on's patches and
 * wiring, Viewpoint's VulkanDevice/SharedImage/Crossing, the NGX wrapper and NVIDIA's
 * runtime. OpenGL draws a known colour gradient at render size; after DLSS has accumulated a
 * few jittered frames, its 1080p output must show the same gradient.
 */
public final class LinuxDlssTest {
    private static final int OUT_W = 1920;
    private static final int OUT_H = 1080;
    /** Viewpoint's scale setting: 100 DLAA, 67 Quality, 58 Balanced, 50 Performance, 33 Ultra Performance. */
    private static final int SCALE = Integer.getInteger("vplinux.test.scale", 50);
    private static final int WARMUP = 32;
    private static final int TIMED = 200;

    public static void main(String[] args) throws Exception {
        TestSupport.loadAddOn();
        long window = TestSupport.glContext();

        String dlss = Upscaling.Kind.DLSS.unavailable();
        TestSupport.expect(dlss == null, "DLSS should be available, Viewpoint says: " + dlss);
        String xess = Upscaling.Kind.XESS.unavailable();
        TestSupport.expect(xess != null && xess.contains("no Linux build"), "XeSS should say it has no Linux build, says: " + xess);
        System.out.println("XeSS reason shown in game: " + xess);

        try (Upscaling up = Upscaling.open(Upscaling.Kind.DLSS, SCALE, OUT_W, OUT_H)) {
            int w = up.frameWidth, h = up.frameHeight;
            System.out.println("render " + w + "x" + h + " -> output " + up.outputWidth + "x" + up.outputHeight);
            TestSupport.expect(SCALE == 100 ? w == OUT_W && h == OUT_H : w < OUT_W && h < OUT_H, "unexpected render size for scale " + SCALE);

            upload(up.color(), w, h);
            fill(up.depth(), GL30.GL_RED, 0.5f);
            GL44.glClearTexImage(up.motion(), 0, GL30.GL_RG, GL11.GL_FLOAT, new float[] {0f, 0f});

            for (int i = 0; i < WARMUP; i++) {
                up.run(frame(i, w, h));
            }
            float error = compare(up.output());
            System.out.printf("mean |output - expected| over the frame: %.4f%n", error);
            TestSupport.expect(error < 0.02f, "DLSS output does not match the input gradient (mean error " + error + ")");

            GL11.glFinish();
            long start = System.nanoTime();
            for (int i = 0; i < TIMED; i++) {
                up.run(frame(WARMUP + i, w, h));
            }
            GL11.glFinish();
            System.out.printf("%d frames: %.2f ms each, including the GL<->Vulkan handoff%n",
                TIMED, (System.nanoTime() - start) / 1e6 / TIMED);
        }

        TestSupport.closeGl(window);
        System.out.println("PASS");
    }

    private static float expected(int channel, float u, float v) {
        return switch (channel) {
            case 0 -> u;
            case 1 -> v;
            case 2 -> 0.25f + 0.5f * u * v;
            default -> 1f;
        };
    }

    private static void upload(int texture, int w, int h) {
        FloatBuffer pixels = BufferUtils.createFloatBuffer(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float u = (x + 0.5f) / w, v = (y + 0.5f) / h;
                for (int c = 0; c < 4; c++) {
                    pixels.put(expected(c, u, v));
                }
            }
        }
        pixels.flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private static void fill(int texture, int format, float value) {
        GL44.glClearTexImage(texture, 0, format, GL11.GL_FLOAT, new float[] {value});
    }

    /** Halton(2,3) jitter in [-0.5, 0.5] render pixels, as a TAA-style renderer would use. */
    private static UpscaleFrame frame(int i, int w, int h) {
        return new UpscaleFrame(halton(i + 1, 2) - 0.5f, halton(i + 1, 3) - 0.5f, w, h, i == 0,
            16.7f, 0.05f, 400f, 1.2f, 1.2247449f, 0f, new float[64], new float[12]);
    }

    private static float halton(int index, int base) {
        float f = 1f, r = 0f;
        for (int i = index; i > 0; i /= base) {
            f /= base;
            r += f * (i % base);
        }
        return r;
    }

    private static float compare(int texture) {
        FloatBuffer out = BufferUtils.createFloatBuffer(OUT_W * OUT_H * 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, out);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        double sum = 0;
        int n = 0;
        for (int y = 8; y < OUT_H - 8; y += 7) {
            for (int x = 8; x < OUT_W - 8; x += 7) {
                float u = (x + 0.5f) / OUT_W, v = (y + 0.5f) / OUT_H;
                for (int c = 0; c < 3; c++) {
                    float got = out.get((y * OUT_W + x) * 4 + c);
                    TestSupport.expect(Float.isFinite(got), "non-finite output at " + x + "," + y);
                    sum += Math.abs(got - expected(c, u, v));
                    n++;
                }
            }
        }
        return (float) (sum / n);
    }
}
