// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package vplinux;

import java.nio.IntBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObjectFD;
import org.lwjgl.opengl.EXTSemaphoreFD;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRExternalMemoryFd;
import org.lwjgl.vulkan.KHRExternalSemaphoreFd;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryGetFdInfoKHR;
import org.lwjgl.vulkan.VkMemoryGetWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreGetFdInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreGetWin32HandleInfoKHR;

/**
 * Linux replacements for the Win32 handle path Viewpoint uses to share images and
 * semaphores between OpenGL and Vulkan. Every patch calls in here and does nothing
 * unless {@link #LINUX} is true, so on Windows the add-on is inert.
 *
 * Win32 handles and POSIX file descriptors differ in one way that matters: importing an
 * fd into OpenGL transfers its ownership to the driver, so Viewpoint's later CloseHandle
 * must become a no-op rather than a close(fd).
 */
public final class Interop {
    public static final boolean LINUX =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");

    /** VK_EXTERNAL_{MEMORY,SEMAPHORE}_HANDLE_TYPE_OPAQUE_WIN32_BIT (same value for both). */
    static final int VK_OPAQUE_WIN32 = 0x2;
    /** VK_EXTERNAL_{MEMORY,SEMAPHORE}_HANDLE_TYPE_OPAQUE_FD_BIT (same value for both). */
    static final int VK_OPAQUE_FD = 0x1;

    private static final Map<String, String> EXTENSIONS = Map.of(
        "VK_KHR_external_memory_win32", "VK_KHR_external_memory_fd",
        "VK_KHR_external_semaphore_win32", "VK_KHR_external_semaphore_fd"
    );

    /** Set by Main once Viewpoint's DLSS entry points at the Linux NGX backend. */
    public static volatile boolean dlssInstalled;

    public static final String DLSS_NOT_WIRED =
        "DLSS's Linux backend could not be wired into this Viewpoint version (see console.txt, [ViewpointLinux])";

    public static final String NO_LINUX_UPSCALER =
        " has no Linux build yet (the Viewpoint Linux add-on provides DLSS only)";

    private Interop() {
    }

    /** Must be called on a thread with a current GL context (Viewpoint only checks there). */
    public static boolean glSupportsFd() {
        GLCapabilities caps = GL.getCapabilities();
        return caps.GL_EXT_memory_object_fd && caps.GL_EXT_semaphore_fd;
    }

    public static int handleType(int type) {
        return LINUX && type == VK_OPAQUE_WIN32 ? VK_OPAQUE_FD : type;
    }

    public static void deviceExtensions(List<String> names) {
        if (LINUX) {
            names.replaceAll(name -> EXTENSIONS.getOrDefault(name, name));
        }
    }

    /** Stands in for vkGetMemoryWin32HandleKHR: writes an fd where Viewpoint expects a HANDLE. */
    public static void exportMemory(VkDevice device, VkMemoryGetWin32HandleInfoKHR win32, PointerBuffer handle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer fd = stack.mallocInt(1);
            VkMemoryGetFdInfoKHR info = VkMemoryGetFdInfoKHR.calloc(stack)
                .sType$Default()
                .memory(win32.memory())
                .handleType(VK_OPAQUE_FD);
            check(KHRExternalMemoryFd.vkGetMemoryFdKHR(device, info, fd), "vkGetMemoryFdKHR");
            handle.put(handle.position(), fd.get(0));
        }
    }

    /** Stands in for vkGetSemaphoreWin32HandleKHR. */
    public static void exportSemaphore(VkDevice device, VkSemaphoreGetWin32HandleInfoKHR win32, PointerBuffer handle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer fd = stack.mallocInt(1);
            VkSemaphoreGetFdInfoKHR info = VkSemaphoreGetFdInfoKHR.calloc(stack)
                .sType$Default()
                .semaphore(win32.semaphore())
                .handleType(VK_OPAQUE_FD);
            check(KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR(device, info, fd), "vkGetSemaphoreFdKHR");
            handle.put(handle.position(), fd.get(0));
        }
    }

    /** Stands in for glImportMemoryWin32HandleEXT; the driver takes ownership of the fd. */
    public static void importMemory(int memory, long size, long fd) {
        EXTMemoryObjectFD.glImportMemoryFdEXT(memory, size, EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, (int) fd);
    }

    /** Stands in for glImportSemaphoreWin32HandleEXT; the driver takes ownership of the fd. */
    public static void importSemaphore(int semaphore, long fd) {
        EXTSemaphoreFD.glImportSemaphoreFdEXT(semaphore, EXTSemaphoreFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, (int) fd);
    }

    private static void check(int result, String call) {
        if (result != 0) {
            throw new IllegalStateException("[ViewpointLinux] " + call + " failed: VkResult " + result);
        }
    }
}
