// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package vplinux;

import java.util.List;
import me.zed_0xff.zombie_buddy.Patch;

/** The device extension list is a mutable ArrayList; swap the win32 names in place. */
@Patch(className = "viewpoint.upscale.VulkanDevice", methodName = "requireExtensions")
public class Patch_DeviceExtensions {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(1) List<String> names) {
        Interop.deviceExtensions(names);
    }
}
