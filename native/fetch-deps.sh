#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 ef3d0c3e
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception
# Downloads the native build's inputs into third_party/ and checks them against pinned hashes:
#  - NVIDIA DLSS SDK v310.9.1: NGX headers, the Linux static NGX library, the licence;
#  - Khronos Vulkan-Headers v1.4.309 (vulkan.h, header-only).
set -euo pipefail
cd "$(dirname "$0")/.."
DLSS=https://raw.githubusercontent.com/NVIDIA/DLSS/v310.9.1
VK_HEADERS=https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.4.309.tar.gz
VK_HEADERS_SHA=437925ada160d86ed763d29dcb9318c1bb0d024d7deaf77bc7c170b8eb6b6f10

mkdir -p third_party/dlss/include third_party/dlss/lib
while read -r _ path; do
  [[ -f "third_party/$path" ]] && continue
  case "$path" in
    dlss/include/*) url="$DLSS/include/${path#dlss/include/}" ;;
    dlss/lib/libnvsdk_ngx.a) url="$DLSS/lib/Linux_x86_64/libnvsdk_ngx.a" ;;
    dlss/LICENSE.txt) url="$DLSS/LICENSE.txt" ;;
  esac
  curl -sSfL -o "third_party/$path" "$url"
done < native/deps.sha256
(cd third_party && sha256sum --quiet -c ../native/deps.sha256)

if [[ ! -f third_party/vulkan-headers/include/vulkan/vulkan.h ]]; then
  curl -sSfL -o third_party/vulkan-headers.tar.gz "$VK_HEADERS"
  echo "$VK_HEADERS_SHA  third_party/vulkan-headers.tar.gz" | sha256sum --quiet -c
  rm -rf third_party/vulkan-headers && mkdir -p third_party/vulkan-headers
  tar xzf third_party/vulkan-headers.tar.gz -C third_party/vulkan-headers --strip-components=1
  rm third_party/vulkan-headers.tar.gz
fi
echo "native dependencies ready in third_party/"
