// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package vplinux;

import viewpoint.upscale.LinuxDlssWiring;

/** ZombieBuddy calls this once when the jar loads; the patches are applied right after. */
public class Main {
    public static void main(String[] args) {
        if (!Interop.LINUX) {
            System.out.println("[ViewpointLinux] loaded; not Linux: every patch passes through unchanged");
            return;
        }
        try {
            LinuxDlssWiring.install();
            Interop.dlssInstalled = true;
            System.out.println("[ViewpointLinux] loaded; Linux: OpenGL/Vulkan sharing through file descriptors, DLSS through NGX");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            System.out.println("[ViewpointLinux] loaded; image sharing patched, but the Linux DLSS backend could not be wired in: " + e);
        }
    }
}
