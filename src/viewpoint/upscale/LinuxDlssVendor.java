// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package viewpoint.upscale;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Set;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkSubmitInfo;
import viewpoint.platform.NativeFiles;

/**
 * DLSS Super Resolution on Linux through NGX, in place of Viewpoint's Windows-only
 * Streamline path (DlssVendor). It takes the same inputs Viewpoint hands DlssVendor and sets
 * them up the way Streamline would for those options:
 *
 *  - colour is LDR (DlssVendor sets colorBuffersHDR false), pre-exposure 1;
 *  - depth is not inverted, motion vectors are at render resolution and not jittered;
 *  - motion vectors are in UV units, so NGX's pixel scale is the render size, which is
 *    exactly UpscaleFrame.motionScaleX/Y (what Viewpoint gives XeSS and FSR as well);
 *  - jitter is in render pixels, passed through as Streamline does;
 *  - the modes follow DlssVendor's table: scale 100/67/58/50/33 is
 *    DLAA/Quality/Balanced/Performance/Ultra Performance.
 *
 * Package viewpoint.upscale only to implement Viewpoint's package-private Vendor interface;
 * the add-on jar and Viewpoint's share ZombieBuddy's class loader.
 */
final class LinuxDlssVendor implements Vendor {
    private static final int[] SCALES = {100, 67, 58, 50, 33};
    /** NVSDK_NGX_PerfQuality_Value: DLAA, MaxQuality, Balanced, MaxPerf, UltraPerformance. */
    private static final int[] QUALITY = {5, 2, 1, 0, 3};
    /** NVSDK_NGX_DLSS_Feature_Flags_MVLowRes. */
    private static final int FLAGS = 1 << 1;
    /** Viewpoint already enables the fd interop extensions (with the add-on's patch). */
    private static final Set<String> ALREADY_ENABLED = Set.of(
        "VK_KHR_external_memory_fd", "VK_KHR_external_semaphore_fd",
        "VK_KHR_external_memory_win32", "VK_KHR_external_semaphore_win32"
    );

    private final long slots = MemoryUtil.nmemCalloc(LinuxNgx.SLOTS, 8);
    private boolean freed;
    private long frames;
    private long context;
    private int renderWidth;
    private int renderHeight;

    LinuxDlssVendor() {
        LinuxNgx.load();
    }

    @Override
    public VulkanDevice.Needs needs() {
        return new VulkanDevice.Needs() {
            @Override
            public List<String> instanceExtensions() {
                return LinuxNgx.instanceExtensions();
            }

            @Override
            public List<String> deviceExtensions(VkInstance instance, VkPhysicalDevice physical) {
                return LinuxNgx.deviceExtensions(instance.address(), physical.address()).stream()
                    .filter(name -> !ALREADY_ENABLED.contains(name))
                    .toList();
            }
        };
    }

    @Override
    public Vendor.Size create(VulkanDevice vk, int scale, int outputWidth, int outputHeight) {
        try {
            return start(vk, scale, outputWidth, outputHeight);
        } catch (RuntimeException e) {
            shutdown();
            throw e;
        }
    }

    private Vendor.Size start(VulkanDevice vk, int scale, int outputWidth, int outputHeight) {
        ByteBuffer feature = MemoryUtil.memUTF8(LinuxNgx.featureDir());
        ByteBuffer data = MemoryUtil.memUTF8(LinuxNgx.dataDir());
        try {
            clear();
            put(0, vk.instance.address());
            put(1, vk.physical.address());
            put(2, vk.device.address());
            put(3, VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"));
            put(4, vk.deviceProcAddr());
            put(5, MemoryUtil.memAddress(feature));
            put(6, MemoryUtil.memAddress(data));
            LinuxNgx.check(LinuxNgx.setup(LinuxNgx.init, slots, "init"), "NGX init");
            context = get(7);
            if (get(8) == 0) {
                throw new IllegalStateException("[ViewpointLinux] NGX reports DLSS unavailable (needs newer driver: "
                    + (get(9) != 0) + ", minimum driver " + get(10) + ", feature init result "
                    + LinuxNgx.describe((int) get(11)) + "); is " + NativeFiles.folder() + " holding libnvidia-ngx-dlss.so?");
            }
        } finally {
            MemoryUtil.memFree(data);
            MemoryUtil.memFree(feature);
        }

        int quality = QUALITY[Vendor.nearest(SCALES, scale)];
        clear();
        put(0, context);
        put(1, outputWidth);
        put(2, outputHeight);
        put(3, quality);
        LinuxNgx.check(LinuxNgx.setup(LinuxNgx.optimal, slots, "optimal"), "DLSS optimal settings");
        renderWidth = (int) get(4);
        renderHeight = (int) get(5);

        createFeature(vk, outputWidth, outputHeight, quality);
        System.out.println("[ViewpointLinux] DLSS ready: " + renderWidth + "x" + renderHeight + " -> "
            + outputWidth + "x" + outputHeight + " (quality value " + quality + ")");
        return new Vendor.Size(renderWidth, renderHeight);
    }

    /** NGX records its setup into a command buffer; run it once on Viewpoint's queue and wait. */
    private void createFeature(VulkanDevice vk, int outputWidth, int outputHeight, int quality) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pool = stack.mallocLong(1);
            VulkanDevice.check(VK10.vkCreateCommandPool(vk.device,
                VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(vk.family), null, pool), "vkCreateCommandPool");
            try {
                PointerBuffer handle = stack.mallocPointer(1);
                VulkanDevice.check(VK10.vkAllocateCommandBuffers(vk.device,
                    VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool.get(0))
                        .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), handle), "vkAllocateCommandBuffers");
                VkCommandBuffer cmd = new VkCommandBuffer(handle.get(0), vk.device);
                VulkanDevice.check(VK10.vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");

                clear();
                put(0, context);
                put(1, cmd.address());
                put(2, renderWidth);
                put(3, renderHeight);
                put(4, outputWidth);
                put(5, outputHeight);
                put(6, quality);
                put(7, FLAGS);
                LinuxNgx.check(LinuxNgx.setup(LinuxNgx.create, slots, "create"), "DLSS create");

                VulkanDevice.check(VK10.vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
                VulkanDevice.check(VK10.vkQueueSubmit(vk.queue,
                    VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd)), VK10.VK_NULL_HANDLE), "vkQueueSubmit");
                VulkanDevice.check(VK10.vkQueueWaitIdle(vk.queue), "vkQueueWaitIdle");
            } finally {
                VK10.vkDestroyCommandPool(vk.device, pool.get(0), null);
            }
        }
    }

    @Override
    public void record(VkCommandBuffer cmd, Vendor.Images images, UpscaleFrame frame) {
        clear();
        put(0, context);
        put(1, cmd.address());
        image(2, images.color());
        image(7, images.depth());
        image(12, images.motion());
        image(17, images.output());
        putDouble(22, frame.jitterX());
        putDouble(23, frame.jitterY());
        putDouble(24, frame.motionScaleX());
        putDouble(25, frame.motionScaleY());
        put(26, frame.reset() ? 1 : 0);
        put(27, images.color().width);
        put(28, images.color().height);
        putDouble(29, frame.frameMs());
        LinuxNgx.check(LinuxNgx.frame(LinuxNgx.evaluate, slots), "DLSS evaluate");
        if (frames++ == 0) {
            System.out.println("[ViewpointLinux] DLSS running: first frame upscaled "
                + images.color().width + "x" + images.color().height + " -> "
                + images.output().width + "x" + images.output().height);
        }
    }

    @Override
    public void close() {
        shutdown();
        if (!freed) {
            freed = true;
            MemoryUtil.nmemFree(slots);
        }
    }

    private void shutdown() {
        if (context != 0L) {
            System.out.println("[ViewpointLinux] DLSS stopped after " + frames + " frames");
            clear();
            put(0, context);
            int result = LinuxNgx.setup(LinuxNgx.shutdown, slots, "shutdown");
            context = 0L;
            if (result != LinuxNgx.SUCCESS) {
                System.out.println("[ViewpointLinux] NGX shutdown: " + LinuxNgx.describe(result));
            }
        }
    }

    private void image(int at, SharedImage image) {
        put(at, image.image);
        put(at + 1, image.view);
        put(at + 2, image.vkFormat);
        put(at + 3, image.width);
        put(at + 4, image.height);
    }

    private void clear() {
        MemoryUtil.memSet(slots, 0, LinuxNgx.SLOTS * 8L);
    }

    private void put(int slot, long value) {
        MemoryUtil.memPutLong(slots + slot * 8L, value);
    }

    private void putDouble(int slot, double value) {
        MemoryUtil.memPutLong(slots + slot * 8L, Double.doubleToRawLongBits(value));
    }

    private long get(int slot) {
        return MemoryUtil.memGetLong(slots + slot * 8L);
    }
}
