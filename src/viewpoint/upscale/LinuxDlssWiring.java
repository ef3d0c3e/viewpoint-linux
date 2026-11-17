// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package viewpoint.upscale;

import java.lang.reflect.Field;
import java.util.function.Supplier;
import viewpoint.platform.LinuxDlssFiles;

/**
 * Points Viewpoint's DLSS entry at the Linux backend, at load time and on Linux only:
 *
 *  - Upscaling.Kind.DLSS makes a LinuxDlssVendor instead of the Streamline DlssVendor;
 *  - NativeFiles.Set.DLSS lists NVIDIA's Linux DLSS library instead of the Windows DLLs
 *    (see viewpoint.platform.LinuxDlssFiles).
 */
public final class LinuxDlssWiring {
    private LinuxDlssWiring() {
    }

    public static void install() throws ReflectiveOperationException {
        Supplier<Vendor> linux = LinuxDlssVendor::new;
        Field vendor = Upscaling.Kind.class.getDeclaredField("vendor");
        vendor.setAccessible(true);
        vendor.set(Upscaling.Kind.DLSS, linux);
        LinuxDlssFiles.install();
    }
}
