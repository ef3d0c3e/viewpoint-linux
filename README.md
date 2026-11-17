# Viewpoint Linux

An add-on for the Viewpoint mod for Project Zomboid. It brings Viewpoint's NVIDIA DLSS to native Linux.
It patches Viewpoint at runtime through ZombieBuddy and contains none of Viewpoint's code.
Every patch and the wiring check for Linux first, so on Windows the add-on does nothing.

## What it does

- **Image sharing.** Viewpoint shares images between OpenGL and Vulkan through Win32 handles.
  The add-on swaps that for POSIX file descriptors (`VK_KHR_external_{memory,semaphore}_fd` and `GL_EXT_{memory_object,semaphore}_fd`).
- **DLSS.** On Windows, Viewpoint runs DLSS through NVIDIA Streamline, which is Windows-only.
  The add-on replaces that backend with `LinuxDlssVendor`, which uses NVIDIA's NGX SDK for Linux.
  It implements Viewpoint's own `Vendor` interface and takes the same inputs Viewpoint gives Streamline.
  All of Viewpoint's DLSS modes work: DLAA, Quality, Balanced, Performance and Ultra Performance.
- **Download.** Viewpoint's DLSS download button now fetches NVIDIA's Linux runtime, `libnvidia-ngx-dlss.so.310.9.1`.
  It comes from the same NVIDIA/DLSS v310.9.1 release the Windows build uses, and Viewpoint's downloader checks its size and SHA-256.
- **FSR 3, XeSS and frame generation** still only exist as Windows DLLs.
  Viewpoint now reports them as having no Linux build, instead of offering a download that cannot load.

### Native code

The NGX SDK for Linux is a static library, so `native/vplinux_ngx.cpp` wraps it in `libvplinux_ngx.so`.
That library exports seven `vpl_*` functions and depends only on libc.
It is packed inside the add-on jar, which the ZombieBuddy signature covers, and copied next to Viewpoint's natives at runtime.
NGX's setup calls need more native stack than a Java thread's default 1 MB, and crash the game without it, so they run on a thread with a 64 MB stack.
The per-frame evaluate runs on Viewpoint's thread; it passes with a 512 KB stack.

NGX's console output is filtered to real warnings and errors.
For the full log, start the game with `VPLINUX_NGX_LOG=1`; in Steam, put `VPLINUX_NGX_LOG=1 %command%` in the launch options.

Requirements:
- Viewpoint 0.1.7 or newer (0.1.7 moved `NativeFiles` to `viewpoint.platform`, which the add-on now uses);
- an NVIDIA RTX card;
- the proprietary driver with `libnvidia-ngx.so.1` (Debian package `libnvidia-ngx1`).

## Patches

| Target | Change on Linux |
|---|---|
| `VulkanDevice.unavailable` | Accepts the `_fd` GL extensions |
| `VulkanDevice.requireExtensions` | Swaps the `_win32` device extension names for `_fd` |
| `VulkanDevice.closeHandle` | Does nothing (OpenGL owns an imported fd) |
| `Vk{ExternalMemoryImageCreateInfo,ExportMemoryAllocateInfo,ExportSemaphoreCreateInfo}.handleTypes(int)` | Changes the OPAQUE_WIN32 bit to OPAQUE_FD |
| `KHRExternal{Memory,Semaphore}Win32.vkGet*Win32HandleKHR` | Calls `vkGet*FdKHR` instead |
| `EXT{MemoryObject,Semaphore}Win32.glImport*Win32HandleEXT` | Calls `glImport*FdEXT` instead |
| `Upscaling.Kind.unsupported`, `FrameGeneration.unsupported` | Reports FSR 3, XeSS and frame generation as having no Linux build |

`LinuxDlssWiring` also uses reflection at load time to swap two values:
- `Upscaling.Kind.DLSS`'s vendor factory, which now makes the Linux backend;
- `NativeFiles.Set.DLSS`'s file list, which now names the Linux runtime.

## Build and test

```sh
./build.sh        # fetches and checks the pinned native dependencies, builds dist/ViewpointLinux
./build.sh test   # also runs both tests outside the game
```

Building needs a JDK 25 or newer (Viewpoint 0.1.7 is compiled for Java 25) and g++. Set `JDK=` if it is not in `/usr/lib/jvm/java-25-openjdk-amd64`.
`native/fetch-deps.sh` downloads the NGX headers and static library from NVIDIA/DLSS v310.9.1, and the Khronos Vulkan headers.
It checks them against `native/deps.sha256`.

Both tests run the add-on's `Main` and apply its patches through ZombieBuddy's own `PatchEngine`, in a hidden OpenGL window:
- **`LinuxInteropTest`** drives Viewpoint's real `VulkanDevice`, `SharedImage` and `Crossing` classes.
  Each frame, OpenGL fills an image, Vulkan copies it to a second image, and OpenGL reads the copy back.
  It runs 200 frames.
- **`LinuxDlssTest`** drives Viewpoint's real `Upscaling` class with DLSS.
  OpenGL draws a known gradient at render size, and the 1080p output must match it.
  Pass `-Dvplinux.test.scale=100|67|58|50|33` to pick the mode.
  For debugging, `-Dvplinux.trace=true` prints a line before each native step.

## Sign

```sh
SSH_AUTH_SOCK=/run/user/$(id -u)/openssh_agent ssh-add ~/.ssh/id_ed25519   # once per login
./build.sh sign
```

This writes `ViewpointLinux.jar.zbs` for ZombieBuddy.
ssh-agent does the signing with the ssh-ed25519 key, so the private key file is never read.
The tool checks the signature against the public key, then again with ZombieBuddy's own `ZBSVerifier`.
The jar build is reproducible: the same sources give the same hash, so the signature stays valid across rebuilds.

ZombieBuddy finds the public key in the Steam profile summary (SteamID64 76561198121246651).
The profile needs this line:

```
JavaModZBS:09e85345aec0f29172f6ec4b808de3248fa8ac1503867330f26d559ea9b623c0
```

## Install

```sh
cp -r dist/ViewpointLinux ~/Zomboid/mods/
```

Enable it after ZombieBuddy and Viewpoint, then approve its jar in ZombieBuddy's prompt.
`console.txt` should then show `[ViewpointLinux] loaded` and a `Transformed:` line for each patched class.

Patches find methods by name, so a Viewpoint update can break them. If one stops matching, ZombieBuddy logs `Method … not found`.

## License

Viewpoint Linux is licensed under the GNU GPL version 3 or later (`LICENSE`), with the additional permissions in `LICENSE-EXCEPTION`.
`NOTICE` has the copyright and third-party notices.

The exception exists because builds statically link NVIDIA's NGX SDK.
NVIDIA does not allow that SDK to be placed under an open-source licence, and the GPL alone would require its source.
The exception lets the add-on be combined with the NGX SDK, and with Project Zomboid, Viewpoint and ZombieBuddy, while those keep their own licences.

The NVIDIA SDK is not in this repository: `native/fetch-deps.sh` downloads it.
Every build ships NVIDIA's licence as `NVIDIA-DLSS-LICENSE.txt` next to `LICENSE`, `LICENSE-EXCEPTION` and `NOTICE`.
If you publish builds, NVIDIA's licence also asks you to credit NVIDIA DLSS (for example in the Workshop description) and to notify NVIDIA before any commercial release.
