// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package viewpoint.upscale;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

/**
 * Standalone check of the add-on, outside the game: applies the vplinux patches through
 * ZombieBuddy's own PatchEngine, then drives Viewpoint's real VulkanDevice, SharedImage and
 * Crossing classes. Lives in viewpoint.upscale only to reach their package-private API.
 *
 * Per frame: OpenGL clears image A to a colour, Vulkan copies A into B, OpenGL reads B back.
 * Getting the colour back proves memory sharing both ways and the semaphore handoff.
 */
public final class LinuxInteropTest {
    private static final int SIZE = 64;
    private static final int VK_FORMAT_R8G8B8A8_UNORM = 37;
    private static final int GL_RGBA8 = 0x8058;
    private static final int VK_IMAGE_LAYOUT_GENERAL = 1;

    public static void main(String[] args) throws Exception {
        TestSupport.loadAddOn();
        long window = TestSupport.glContext();

        String reason = VulkanDevice.unavailable();
        expect(reason == null, "VulkanDevice.unavailable() should be null, was: " + reason);
        String xess = Upscaling.Kind.XESS.unsupported();
        expect(xess != null && xess.contains("no Linux build"), "XeSS reason should name Linux, was: " + xess);
        expect(Upscaling.Kind.DLSS.unsupported() == null, "DLSS should be supported: " + Upscaling.Kind.DLSS.unsupported());
        String frameGen = FrameGeneration.unsupported();
        expect(frameGen != null && frameGen.contains("no Linux build"), "frame generation reason should name Linux, was: " + frameGen);

        int failures = 0;
        try (VulkanDevice vk = VulkanDevice.open(new VulkanDevice.Needs() {});
             Crossing crossing = new Crossing(vk);
             SharedImage a = new SharedImage(vk, VK_FORMAT_R8G8B8A8_UNORM, GL_RGBA8, SIZE, SIZE);
             SharedImage b = new SharedImage(vk, VK_FORMAT_R8G8B8A8_UNORM, GL_RGBA8, SIZE, SIZE)) {
            System.out.println("Vulkan device, 2 shared images and 2 shared semaphores created");

            int[][] colours = {{255, 0, 0, 255}, {0, 255, 0, 255}, {0, 0, 255, 255}, {12, 34, 56, 78}};
            for (int frame = 0; frame < 200; frame++) {
                int[] c = colours[frame % colours.length];
                clear(a.texture, c);
                clear(b.texture, new int[] {0, 0, 0, 0});

                crossing.handOver(a, b);
                VkCommandBuffer cmd = vk.begin();
                crossing.acquire(cmd, a, b);
                copy(cmd, a, b);
                crossing.release(cmd, a, b);
                crossing.submit(cmd);
                crossing.takeBack(a, b);

                int[] got = read(b.texture);
                if (!java.util.Arrays.equals(got, c)) {
                    failures++;
                    if (failures <= 5) {
                        System.out.println("frame " + frame + ": expected " + java.util.Arrays.toString(c) + ", got " + java.util.Arrays.toString(got));
                    }
                }
            }
            vk.idle();
            System.out.println("200 frames GL -> Vulkan -> GL, mismatches: " + failures);
        }

        TestSupport.closeGl(window);
        expect(failures == 0, failures + " frames came back wrong");
        System.out.println("PASS");
    }

    private static void clear(int texture, int[] rgba) {
        ByteBuffer colour = BufferUtils.createByteBuffer(4);
        for (int v : rgba) {
            colour.put((byte) v);
        }
        colour.flip();
        GL44.glClearTexImage(texture, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, colour);
    }

    private static int[] read(int texture) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        int[] first = {pixels.get(0) & 255, pixels.get(1) & 255, pixels.get(2) & 255, pixels.get(3) & 255};
        for (int i = 0; i < SIZE * SIZE * 4; i++) {
            if ((pixels.get(i) & 255) != first[i % 4]) {
                return new int[] {-1, -1, -1, -1};
            }
        }
        return first;
    }

    private static void copy(VkCommandBuffer cmd, SharedImage from, SharedImage to) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.extent().set(SIZE, SIZE, 1);
            VK10.vkCmdCopyImage(cmd, from.image, VK_IMAGE_LAYOUT_GENERAL, to.image, VK_IMAGE_LAYOUT_GENERAL, region);
        }
    }

    private static void expect(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
