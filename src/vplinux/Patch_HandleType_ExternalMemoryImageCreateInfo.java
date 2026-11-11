// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package vplinux;

import me.zed_0xff.zombie_buddy.Patch;

/** The setter, not the getter: the int argument narrows the match. Win32 bit becomes the fd bit. */
@Patch(className = "org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo", methodName = "handleTypes", strictMatch = true)
public class Patch_HandleType_ExternalMemoryImageCreateInfo {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(value = 0, readOnly = false) int types) {
        types = Interop.handleType(types);
    }
}
