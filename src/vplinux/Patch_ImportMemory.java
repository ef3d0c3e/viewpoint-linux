// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package vplinux;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "org.lwjgl.opengl.EXTMemoryObjectWin32", methodName = "glImportMemoryWin32HandleEXT", strictMatch = true)
public class Patch_ImportMemory {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(
        @Patch.Argument(0) int memory, @Patch.Argument(1) long size, @Patch.Argument(2) int handleType, @Patch.Argument(3) long handle
    ) {
        if (!Interop.LINUX) {
            return false;
        }
        Interop.importMemory(memory, size, handle);
        return true;
    }
}
