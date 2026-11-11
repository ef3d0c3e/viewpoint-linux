// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package vplinux;

import me.zed_0xff.zombie_buddy.Patch;

/** Viewpoint only accepts the Win32 GL extensions; accept the fd ones on Linux. */
@Patch(className = "viewpoint.upscale.VulkanDevice", methodName = "unavailable")
public class Patch_InteropCheck {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String reason) {
        if (reason != null && Interop.LINUX && Interop.glSupportsFd()) {
            reason = null;
        }
    }
}
