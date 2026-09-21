# Terminal native component patches

`proot-bionic-headers.patch` fills in the standard library declarations the Android NDK build needs, provided under PRoot's GPL-2.0-or-later license.

`shmem-app-temp.patch` places the shared-memory key temp files under the caller-provided `PROOT_TMP_DIR`, validates the path length, and returns an error when the filesystem refuses creation, provided under libandroid-shmem's BSD-3-Clause license.

Patches apply only to the build copy. Pinned download sources and SHA-256 hashes live in the parent build script; source caches and build outputs go under `.analysis/`. The build script uses GPL-3.0-or-later and does not change the license of the su app or the standalone PTY program.
