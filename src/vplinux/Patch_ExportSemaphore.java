// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package vplinux;

import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSemaphoreGetWin32HandleInfoKHR;
import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "org.lwjgl.vulkan.KHRExternalSemaphoreWin32", methodName = "vkGetSemaphoreWin32HandleKHR", strictMatch = true)
public class Patch_ExportSemaphore {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(
        @Patch.Argument(0) VkDevice device, @Patch.Argument(1) VkSemaphoreGetWin32HandleInfoKHR info, @Patch.Argument(2) PointerBuffer handle
    ) {
        if (!Interop.LINUX) {
            return false;
        }
        Interop.exportSemaphore(device, info, handle);
        return true;
    }
}
