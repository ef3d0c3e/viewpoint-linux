// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package vplinux;

import me.zed_0xff.zombie_buddy.Patch;
import viewpoint.upscale.Upscaling;

/**
 * DLSS runs through the add-on's NGX backend; FSR 3 and XeSS still only have Windows DLLs,
 * which Viewpoint would otherwise offer to download. Say so instead.
 */
@Patch(className = "viewpoint.upscale.Upscaling$Kind", methodName = "unsupported")
public class Patch_UpscalerReason {
    @Patch.OnExit
    public static void exit(@Patch.This Upscaling.Kind kind, @Patch.Return(readOnly = false) String reason) {
        if (reason == null && Interop.LINUX) {
            if (kind != Upscaling.Kind.DLSS) {
                reason = kind.label + Interop.NO_LINUX_UPSCALER;
            } else if (!Interop.dlssInstalled) {
                reason = Interop.DLSS_NOT_WIRED;
            }
        }
    }
}
