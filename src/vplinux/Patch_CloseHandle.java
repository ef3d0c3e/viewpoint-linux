// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package vplinux;

import me.zed_0xff.zombie_buddy.Patch;

/** On Linux every exported fd was imported by OpenGL, which now owns it: nothing to close. */
@Patch(className = "viewpoint.upscale.VulkanDevice", methodName = "closeHandle")
public class Patch_CloseHandle {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.Argument(0) long handle) {
        return Interop.LINUX;
    }
}
