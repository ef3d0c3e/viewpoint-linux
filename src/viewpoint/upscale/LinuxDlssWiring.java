// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package viewpoint.upscale;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Supplier;

/**
 * Points Viewpoint's DLSS entry at the Linux backend, at load time and on Linux only:
 *
 *  - Upscaling.Kind.DLSS makes a LinuxDlssVendor instead of the Streamline DlssVendor;
 *  - NativeFiles.Set.DLSS lists NVIDIA's Linux DLSS library instead of the Windows DLLs,
 *    so Viewpoint's own download button fetches it, size- and SHA-256-checked, from the
 *    same NVIDIA/DLSS release (v310.9.1) the Windows build uses.
 */
public final class LinuxDlssWiring {
    static final NativeFiles.Library DLSS_LINUX = new NativeFiles.Library(
        "libnvidia-ngx-dlss.so.310.9.1",
        59481944L,
        "7561f16cab74e2b7ccf791bf44bb9cc43abb44bcf78f33c5663520ad4e857e02",
        "https://raw.githubusercontent.com/NVIDIA/DLSS/v310.9.1/lib/Linux_x86_64/rel/libnvidia-ngx-dlss.so.310.9.1",
        null
    );

    private LinuxDlssWiring() {
    }

    public static void install() throws ReflectiveOperationException {
        Supplier<Vendor> linux = LinuxDlssVendor::new;
        set(Upscaling.Kind.class, Upscaling.Kind.DLSS, "vendor", linux);
        set(NativeFiles.Set.class, NativeFiles.Set.DLSS, "libraries", List.of(DLSS_LINUX));
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
