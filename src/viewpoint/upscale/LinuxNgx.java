// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

package viewpoint.upscale;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import viewpoint.platform.NativeFiles;

/**
 * Binding to libvplinux_ngx.so, the add-on's wrapper around NVIDIA's NGX SDK (see
 * native/vplinux_ngx.cpp). Every wrapper function takes one array of 8-byte slots.
 *
 * The library is shipped inside the add-on jar, which is what ZombieBuddy's signature
 * covers, and copied next to Viewpoint's other native files before loading.
 *
 * NGX's setup calls (feature discovery, init, create, shutdown) need far more native stack
 * than a Java thread's default 1 MB, and crash the game outright without it, so they run on
 * one thread with a large stack. The per-frame evaluate stays on the caller's thread.
 */
final class LinuxNgx {
    static final String LIBRARY = "libvplinux_ngx.so";
    private static final String RESOURCE = "/vplinux/native/" + LIBRARY;

    static final int SUCCESS = 1;
    static final int SLOTS = 32;
    private static final int NAMES_BYTES = 16384;

    private static final long SETUP_STACK_BYTES = 64L << 20;
    private static final ExecutorService SETUP = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(null, task, "ViewpointLinux NGX setup", SETUP_STACK_BYTES);
        thread.setDaemon(true);
        return thread;
    });

    private static SharedLibrary library;
    private static long instanceExtensions;
    private static long deviceExtensions;
    static long init;
    static long optimal;
    static long create;
    static long evaluate;
    static long shutdown;

    private LinuxNgx() {
    }

    static synchronized void load() {
        if (library != null) {
            return;
        }
        File file = NativeFiles.file(LIBRARY);
        try {
            install(file.toPath());
        } catch (IOException e) {
            throw new IllegalStateException("[ViewpointLinux] could not place " + LIBRARY + " in " + file.getParent() + ": " + e, e);
        }
        trace("load " + file);
        library = Library.loadNative(LinuxNgx.class, "viewpoint", file.getAbsolutePath());
        instanceExtensions = function("vpl_instance_extensions");
        deviceExtensions = function("vpl_device_extensions");
        init = function("vpl_init");
        optimal = function("vpl_optimal");
        create = function("vpl_create");
        evaluate = function("vpl_evaluate");
        shutdown = function("vpl_shutdown");
    }

    /** Copies the jar's library out unless an identical copy is already there. */
    private static void install(Path target) throws IOException {
        byte[] bytes;
        try (InputStream in = LinuxNgx.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException(RESOURCE + " is missing from the add-on jar");
            }
            bytes = in.readAllBytes();
        }
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(LIBRARY + ".part");
        Files.write(part, bytes);
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static long function(String name) {
        long address = library.getFunctionAddress(name);
        if (address == 0L) {
            throw new IllegalStateException("[ViewpointLinux] " + LIBRARY + " has no " + name);
        }
        return address;
    }

    /** Where NGX looks for libnvidia-ngx-dlss.so.* : Viewpoint's native download folder. */
    static String featureDir() {
        return NativeFiles.folder().getAbsolutePath();
    }

    /** Where NGX may write logs and its own updates. */
    static String dataDir() {
        File dir = new File(NativeFiles.folder(), "ngx");
        dir.mkdirs();
        return dir.getAbsolutePath();
    }

    static List<String> instanceExtensions() {
        return names(instanceExtensions, 0, 1, new Object[] {featureDir(), dataDir()}, 2, new long[0]);
    }

    static List<String> deviceExtensions(long instance, long physical) {
        return names(deviceExtensions, 2, 3, new Object[] {featureDir(), dataDir()}, 4, new long[] {instance, physical});
    }

    /** Calls one of the two extension queries, which write "a\0b\0\0" into a buffer. */
    private static List<String> names(long function, int bufferSlot, int sizeSlot, Object[] dirs, int dirSlot, long[] leading) {
        long slots = MemoryUtil.nmemCalloc(SLOTS, 8);
        ByteBuffer buffer = MemoryUtil.memAlloc(NAMES_BYTES);
        ByteBuffer feature = MemoryUtil.memUTF8((String) dirs[0]);
        ByteBuffer data = MemoryUtil.memUTF8((String) dirs[1]);
        try {
            for (int i = 0; i < leading.length; i++) {
                MemoryUtil.memPutLong(slots + i * 8L, leading[i]);
            }
            MemoryUtil.memPutLong(slots + bufferSlot * 8L, MemoryUtil.memAddress(buffer));
            MemoryUtil.memPutLong(slots + sizeSlot * 8L, NAMES_BYTES);
            MemoryUtil.memPutLong(slots + dirSlot * 8L, MemoryUtil.memAddress(feature));
            MemoryUtil.memPutLong(slots + (dirSlot + 1) * 8L, MemoryUtil.memAddress(data));
            check(setup(function, slots, "extension query"), "NGX extension query");

            List<String> names = new ArrayList<>();
            int at = 0;
            while (buffer.get(at) != 0) {
                int end = at;
                while (buffer.get(end) != 0) {
                    end++;
                }
                byte[] name = new byte[end - at];
                buffer.get(at, name);
                names.add(new String(name, StandardCharsets.US_ASCII));
                at = end + 1;
            }
            return names;
        } finally {
            MemoryUtil.memFree(data);
            MemoryUtil.memFree(feature);
            MemoryUtil.memFree(buffer);
            MemoryUtil.nmemFree(slots);
        }
    }

    private static final boolean TRACE = Boolean.getBoolean("vplinux.trace");

    /** -Dvplinux.trace=true: a line before each native step, to place a crash. */
    static void trace(String step) {
        if (TRACE) {
            System.out.println("[ViewpointLinux] trace: " + step);
            System.out.flush();
        }
    }

    /** Runs a setup call on the large-stack thread and waits for it. */
    static int setup(long function, long slots, String step) {
        trace(step);
        try {
            return SETUP.submit(() -> JNI.callPI(slots, function)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("[ViewpointLinux] interrupted during NGX " + step, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("[ViewpointLinux] NGX " + step + " failed", e.getCause());
        }
    }

    /** Runs a per-frame call on the caller's thread. */
    static int frame(long function, long slots) {
        return JNI.callPI(slots, function);
    }

    static void check(int result, String what) {
        if (result != SUCCESS) {
            throw new IllegalStateException("[ViewpointLinux] " + what + " failed: " + describe(result));
        }
    }

    static String describe(int result) {
        return switch (result) {
            case -1 -> "no NGX context";
            case -2 -> "extension list too long";
            case -3 -> "DLSS offers no render size for this mode";
            default -> "NVSDK_NGX_Result 0x" + Integer.toHexString(result);
        };
    }
}
