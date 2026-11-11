// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package vplinux;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "viewpoint.upscale.FrameGeneration", methodName = "unsupported")
public class Patch_FrameGenReason {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String reason) {
        if (reason == null && Interop.LINUX) {
            reason = "FSR 3 frame generation" + Interop.NO_LINUX_UPSCALER;
        }
    }
}
