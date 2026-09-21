# Native terminal components

su ships its terminal helpers as standalone ELF executables inside `jniLibs`. The files
use the `.so` suffix so the Android installer extracts them to the read-only
`nativeLibraryDir`; they are never loaded through JNI and no Android native helper is
executed from a writable directory.

| Binary | ABI | Purpose |
| --- | --- | --- |
| `libeta_pty.so` | arm64-v8a, x86_64, armeabi-v7a, x86 | Real pseudo-terminals for plain Android Shell and Linux sessions |
| `libproot_exec.so` | arm64-v8a, x86_64 | Runs the Linux user space under the app UID |
| `libproot_loader.so` | arm64-v8a, x86_64 | Same-ABI loader produced by the same PRoot build |

PRoot only supports the 64-bit Linux rootfs matching the host; it executes no foreign
instruction set and grants no device root privileges. PRoot statically links talloc and
libandroid-shmem and depends only on Android system libraries at runtime. The built
ELFs use 16 KiB page alignment.

## Runtime interface

PTY arguments are `rows cols -- executable args...`; stdin and stdout carry raw
terminal bytes. Ctrl-C travels through the PTY to the current foreground process group;
a closed input pipe sends terminal EOF; closing the window, parent-process exit, or a
broken output pipe tears down the terminal process group. Exit codes preserve the child
status. The interactive window size is passed at launch, and callers resync it when the
real terminal delivers SIGWINCH.

PRoot callers must set `PROOT_LOADER` to the current
`nativeLibraryDir/libproot_loader.so` and `PROOT_TMP_DIR` to a su-writable private temp
directory. A missing loader never falls back to another app's install path. The shared-
memory patch uses the same temp directory and never depends on the global `/tmp`.

## Rebuilding

Builds need the Android NDK matching the `ndk` entry in `gradle/libs.versions.toml`,
plus Python 3, GNU make, patch, and tar, running on macOS or Linux:

```sh
ANDROID_NDK_HOME=/path/to/android-ndk-r29 scripts/build-terminal-native.sh
```

The script first checks the NDK's `source.properties` against the version directory,
then uses the pinned source archives shipped with the APK and verifies their SHA-256,
downloading from the pinned upstream URLs only when sources are missing. Third-party
source versions, URLs, digests, and compile flags follow the script. `ETA_NATIVE_SOURCES`
and `ETA_NATIVE_BUILD` may override the source cache and scratch build directory.
Build logs stay in the build directory and contain no model configuration or user files.

The script produces every ELF and automatically packs the actual script, version
directory, PTY sources, and patches into
`app/src/main/assets/native-sources/eta-native-build.tgz`, which must not be edited by
hand. The APK's `assets/native-sources` also carries the pristine PRoot, talloc, and
libandroid-shmem sources; source resources use the `.tgz` suffix so Android resource
packaging never auto-extracts a `.gz` file and changes names or checksums.
`assets/licenses` carries the license texts. Extract the build bundle to restore the
repo-relative paths, then pass the pristine-source directory back via
`ETA_NATIVE_SOURCES` to rebuild the bundled components.

Source bundles and regenerated binaries must be updated together. The bundled
third-party programs and patches keep their own open-source licenses, which the su main
project's non-commercial license does not restrict; see
[Third-party notices](THIRD_PARTY_NOTICES.md).
