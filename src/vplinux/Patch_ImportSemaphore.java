// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package vplinux;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "org.lwjgl.opengl.EXTSemaphoreWin32", methodName = "glImportSemaphoreWin32HandleEXT", strictMatch = true)
public class Patch_ImportSemaphore {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.Argument(0) int semaphore, @Patch.Argument(1) int handleType, @Patch.Argument(2) long handle) {
        if (!Interop.LINUX) {
            return false;
        }
        Interop.importSemaphore(semaphore, handle);
        return true;
    }
}
