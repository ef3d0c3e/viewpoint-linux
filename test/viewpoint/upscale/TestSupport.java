// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package viewpoint.upscale;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

/** What ZombieBuddy and the game do before Viewpoint's upscaling runs, minus the game. */
final class TestSupport {
    private TestSupport() {
    }

    /** Calls the add-on's Main, then applies its patches with ZombieBuddy's own PatchEngine. */
    static void loadAddOn() throws Exception {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader");
        Field field = loader.getDeclaredField("g_instrumentation");
        field.setAccessible(true);
        field.set(null, instrumentation);
        vplinux.Main.main(new String[0]);
        Class.forName("me.zed_0xff.zombie_buddy.PatchEngine")
            .getMethod("applyPatches", String.class, ClassLoader.class)
            .invoke(null, "vplinux", ClassLoader.getSystemClassLoader());
    }

    /** A hidden window whose OpenGL context stands in for the game's. */
    static long glContext() {
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("glfwInit failed");
        }
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(64, 64, "vplinux test", 0L, 0L);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("GL: " + GL11.glGetString(GL11.GL_RENDERER) + " / " + GL11.glGetString(GL11.GL_VERSION));
        return window;
    }

    static void closeGl(long window) {
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    static void expect(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
