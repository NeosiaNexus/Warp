#!/usr/bin/env bash
# Builds the libdeflate release pinned in libdeflate.env as a shared library for one platform.
#
#   jni/native/build-libdeflate.sh <platform> <output-dir>
#   jni/native/build-libdeflate.sh host <output-dir>       # the platform this machine is
#   jni/native/build-libdeflate.sh --print-host            # prints that platform and exits
#
# Writes <output-dir>/<platform>/<library>, then checks it: it exports libdeflate's zlib API, needs
# no library but the C library, runs on glibc 2.17 (glibc builds), has no executable stack (glibc
# 2.41 refuses to load such a library) and targets the right architecture.
#
#   platform              library           built with
#   linux-x86_64          libdeflate.so     the machine's gcc, glibc 2.17 or later at run time
#   linux-aarch64         libdeflate.so     the machine's gcc, glibc 2.17 or later at run time
#   linux-x86_64-musl     libdeflate.so     gcc on Alpine (musl)
#   linux-aarch64-musl    libdeflate.so     gcc on Alpine (musl)
#   macos-x86_64          libdeflate.dylib  Apple clang, macOS 11 or later, ad-hoc signed
#   macos-aarch64         libdeflate.dylib  Apple clang, macOS 11 or later, ad-hoc signed
#   windows-x86_64        deflate.dll       LLVM clang for MSVC, C runtime linked in (needs only
#                                           KERNEL32.dll)
#
# The source tarball is downloaded once into $WARP_NATIVE_CACHE (default ~/.cache/warp-native) and
# checked against LIBDEFLATE_SHA256 before every build. Set CC to use another compiler.
#
# Flags: -O2, plus -fno-tree-vectorize with gcc: gcc 12 and later auto-vectorise at -O2, which made
# libdeflate's level 1 compression 1.8 times slower (docs/research/deflate-peek-and-codec-
# performance.md, section 4.4). No -march: libdeflate picks its SIMD code at run time. Only the
# zlib format is built (no gzip, no CRC-32) and only libdeflate's API is exported.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=libdeflate.env
. "$here/libdeflate.env"

# The functions Warp binds (dev.warp.jni.Libdeflate); the library must export each of them.
required_symbols=(
  libdeflate_alloc_compressor
  libdeflate_free_compressor
  libdeflate_zlib_compress
  libdeflate_zlib_compress_bound
  libdeflate_alloc_decompressor
  libdeflate_free_decompressor
  libdeflate_zlib_decompress
)
# The newest glibc symbol version a glibc build may need: glibc 2.17 is the oldest the JDK runs on.
max_glibc=2.17

die() { echo "build-libdeflate: $*" >&2; exit 1; }

host_platform() {
  local os arch
  case "$(uname -s)" in
    Linux) os=linux ;;
    Darwin) os=macos ;;
    MINGW* | MSYS* | CYGWIN*) os=windows ;;
    *) die "unsupported operating system: $(uname -s)" ;;
  esac
  case "$(uname -m)" in
    x86_64 | amd64 | AMD64) arch=x86_64 ;;
    aarch64 | arm64 | ARM64) arch=aarch64 ;;
    *) die "unsupported architecture: $(uname -m)" ;;
  esac
  if [ "$os" = linux ] && compgen -G '/lib/ld-musl-*.so.1' > /dev/null; then
    echo "$os-$arch-musl"
  else
    echo "$os-$arch"
  fi
}

sha256_of() {
  if command -v sha256sum > /dev/null; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

if [ "${1:-}" = --print-host ]; then
  host_platform
  exit 0
fi
[ $# -eq 2 ] || die "usage: build-libdeflate.sh <platform|host> <output-dir> (or --print-host)"
platform=$1
[ "$platform" = host ] && platform=$(host_platform)
mkdir -p "$2"
output_root=$(cd "$2" && pwd)

case "$platform" in
  linux-x86_64 | linux-aarch64 | linux-x86_64-musl | linux-aarch64-musl) library=libdeflate.so ;;
  macos-x86_64 | macos-aarch64) library=libdeflate.dylib ;;
  windows-x86_64) library=deflate.dll ;;
  *) die "unknown platform: $platform" ;;
esac

# ---------------------------------------------------------------------------
# Source: download once, verify every time
# ---------------------------------------------------------------------------

cache=${WARP_NATIVE_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/warp-native}
tarball="$cache/libdeflate-$LIBDEFLATE_VERSION.tar.gz"
mkdir -p "$cache"
if [ ! -f "$tarball" ]; then
  url="https://github.com/ebiggers/libdeflate/releases/download/v$LIBDEFLATE_VERSION/libdeflate-$LIBDEFLATE_VERSION.tar.gz"
  echo "Downloading $url"
  partial=$(mktemp "$cache/download.XXXXXX")
  curl -sSfL --retry 3 -o "$partial" "$url" || { rm -f "$partial"; die "download failed: $url"; }
  mv -f "$partial" "$tarball"
fi
actual=$(sha256_of "$tarball")
if [ "$actual" != "$LIBDEFLATE_SHA256" ]; then
  rm -f "$tarball"
  die "checksum mismatch for $tarball: expected $LIBDEFLATE_SHA256, got $actual (file deleted)"
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
tar -xzf "$tarball" -C "$work"
src="$work/libdeflate-$LIBDEFLATE_VERSION"
sources=(
  "$src/lib/adler32.c"
  "$src/lib/deflate_compress.c"
  "$src/lib/deflate_decompress.c"
  "$src/lib/zlib_compress.c"
  "$src/lib/zlib_decompress.c"
  "$src/lib/utils.c"
  "$src/lib/x86/cpu_features.c"
  "$src/lib/arm/cpu_features.c"
)

out_dir="$output_root/$platform"
out="$out_dir/$library"
mkdir -p "$out_dir"
rm -f "$out"

# ---------------------------------------------------------------------------
# Compile and link
# ---------------------------------------------------------------------------

common=(-O2 -DNDEBUG -fvisibility=hidden "-ffile-prefix-map=$src=libdeflate-$LIBDEFLATE_VERSION")
case "$platform" in
  linux-*)
    cc=${CC:-gcc}
    flags=("${common[@]}" -fPIC)
    if "$cc" --version | grep -qi 'gcc\|free software foundation'; then
      flags+=(-fno-tree-vectorize)
      # gcc's outline atomics call __getauxval from a constructor, which crashes in dlopen on musl
      # (netty/netty#17348). libdeflate uses no atomics; this keeps any helper out regardless.
      [[ "$platform" == linux-aarch64* ]] && flags+=(-mno-outline-atomics)
    fi
    "$cc" "${flags[@]}" -shared -o "$out" "${sources[@]}" \
      -Wl,-soname,libdeflate.so -Wl,-z,defs -Wl,-z,noexecstack -Wl,-z,relro -Wl,-z,now -Wl,-O1
    strip --strip-unneeded "$out"
    ;;
  macos-*)
    cc=${CC:-clang}
    arch=${platform#macos-}
    [ "$arch" = aarch64 ] && arch=arm64
    "$cc" "${common[@]}" -arch "$arch" -mmacosx-version-min=11.0 -dynamiclib \
      -install_name @rpath/libdeflate.dylib -o "$out" "${sources[@]}"
    strip -x "$out"
    # Apple silicon only runs signed code; strip may leave the linker's ad-hoc signature stale.
    codesign --force --sign - "$out"
    ;;
  windows-*)
    cc=${CC:-clang}
    # LIBDEFLATE_DLL exports the API with __declspec(dllexport); /Brepro makes the build
    # reproducible (no timestamp); the static C runtime spares users the VC++ redistributable.
    "$cc" "${common[@]}" -DLIBDEFLATE_DLL -fms-runtime-lib=static -fuse-ld=lld -shared \
      -o "$out" "${sources[@]}" -Wl,/Brepro
    rm -f "$out_dir"/*.lib "$out_dir"/*.exp "$out_dir"/*.pdb
    ;;
esac

# ---------------------------------------------------------------------------
# Check what was built
# ---------------------------------------------------------------------------

check_exports() {
  local exported=$1 symbol
  for symbol in "${required_symbols[@]}"; do
    grep -qx "$symbol" <<< "$exported" || die "$out does not export $symbol"
  done
}

case "$platform" in
  linux-*)
    needed=$(readelf -dW "$out" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
    case "$platform" in
      *-musl) [[ "$needed" =~ ^libc\.musl-[a-z0-9_]+\.so\.1$ ]] || die "$out needs: $needed (expected musl's libc only)" ;;
      *) [ "$needed" = libc.so.6 ] || die "$out needs: $needed (expected libc.so.6 only)" ;;
    esac
    if [[ "$platform" != *-musl ]]; then
      newest=$(objdump -T "$out" | grep -o 'GLIBC_[0-9.]*' | sed 's/GLIBC_//' | sort -V | tail -1)
      [ "$(printf '%s\n%s\n' "$newest" "$max_glibc" | sort -V | tail -1)" = "$max_glibc" ] \
        || die "$out needs glibc $newest, newer than $max_glibc"
    fi
    readelf -lW "$out" | grep GNU_STACK | grep -q 'RW ' || die "$out asks for an executable stack"
    if nm -D --undefined-only "$out" | grep -q '__getauxval\|__aarch64_'; then
      die "$out calls gcc's outline atomics helpers, which crash on musl"
    fi
    machine=$(readelf -hW "$out" | sed -n 's/.*Machine: *//p')
    case "$platform" in
      linux-x86_64*) [[ "$machine" == *X86-64* ]] || die "$out is for $machine" ;;
      linux-aarch64*) [[ "$machine" == *AArch64* ]] || die "$out is for $machine" ;;
    esac
    check_exports "$(nm -D --defined-only "$out" | awk '{print $3}')"
    ;;
  macos-*)
    libraries=$(otool -L "$out" | tail -n +2 | awk '{print $1}' | grep -v '@rpath/libdeflate.dylib')
    [ "$libraries" = /usr/lib/libSystem.B.dylib ] || die "$out links: $libraries (expected libSystem only)"
    [ "$(lipo -archs "$out")" = "$arch" ] || die "$out is for $(lipo -archs "$out"), not $arch"
    codesign --verify "$out"
    check_exports "$(nm -gU "$out" | awk '{print $3}' | sed 's/^_//')"
    ;;
  windows-*)
    dump=$(llvm-objdump -p "$out")
    imports=$(sed -n 's/^ *DLL Name: *//p' <<< "$dump" | tr '[:upper:]' '[:lower:]' | sort -u)
    [ "$imports" = kernel32.dll ] || die "$out imports: $imports (expected KERNEL32.dll only)"
    grep -q 'Machine.*AMD64\|machine.*8664' <<< "$dump$(llvm-readobj --file-headers "$out")" \
      || die "$out is not an x86-64 DLL"
    check_exports "$(llvm-readobj --coff-exports "$out" | sed -n 's/^ *Name: *//p')"
    ;;
esac

echo "Built libdeflate $LIBDEFLATE_VERSION for $platform: $out ($(wc -c < "$out" | tr -d ' ') bytes, sha256 $(sha256_of "$out"))"
