// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package vplinux;

import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryGetWin32HandleInfoKHR;
import me.zed_0xff.zombie_buddy.Patch;

/** Skipped methods return 0, which is VK_SUCCESS. */
@Patch(className = "org.lwjgl.vulkan.KHRExternalMemoryWin32", methodName = "vkGetMemoryWin32HandleKHR", strictMatch = true)
public class Patch_ExportMemory {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(
        @Patch.Argument(0) VkDevice device, @Patch.Argument(1) VkMemoryGetWin32HandleInfoKHR info, @Patch.Argument(2) PointerBuffer handle
    ) {
        if (!Interop.LINUX) {
            return false;
        }
        Interop.exportMemory(device, info, handle);
        return true;
    }
}
