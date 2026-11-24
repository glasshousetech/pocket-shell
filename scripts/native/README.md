# Android x86_64 PRoot compatibility

PocketShell's x86_64 runtime translates legacy Linux syscalls rejected by Android 17's application seccomp policy into their supported equivalents. The ARM runtimes are unchanged. Source is GPL-2.0 under the upstream repository's COPYING file; talloc remains LGPL-3.0-or-later.

Pinned sources:
- https://github.com/green-green-avk/proot/tree/b40accf05dfddbaf44758aa666ade79c57255005
- https://github.com/green-green-avk/build-proot-android/tree/01f83b8841358450c78333d1b33ab30d4943bec4
- Reference: https://github.com/termux/proot/blob/52d65a4dc140ca554d241a1438ddd4f6b00c9813/src/tracee/seccomp.c

Run build-runtime.py --work <isolated-build-directory> --ndk <NDK-27.2.12479018> --jobs 2 on Linux. It verifies downloaded source hashes, applies android-x86-syscalls.patch, and produces output/libproot.so plus a provenance receipt. Android API 26 matches the application's minimum; ELF pages support 16 KB alignment. Build parallelism is configurable; the default avoids monopolizing a shared build server.

The behavior probe is built with: gcc -static -O2 -Wall -Wextra -o runtime-syscalls-x86_64 scripts/native/runtime-syscalls.c. Its test-only asset is installed in the instrumentation APK, never the production app. InstalledLinuxRuntimeTest runs it through the actual production PRoot binary in Android's application sandbox after Linux setup; also verifies live Android DNS and the real installed toolchain. Invoke this class explicitly after setup. LinuxDnsTest covers DNS changes, offline preservation, overlapping writes and removal without requiring a rootfs.

The 20 probe checks cover guest-path translation, mkdir/access errors, dup2 descriptor and CLOEXEC semantics, legacy pipe/poll, rename/unlink and cleanup. The unmodified x86_64 binary fails the first workspace creation on Android 17; the patched binary passes all 20. Final signed-artifact test results are recorded separately from this baseline experiment.

The combined runtime also translates musl fork to clone and restores child registers at first stop. UPSTREAM.md records the upstream backport and fork-regs.c provenance. GuestGroupNamesAndroidTest verifies the fork probe, numeric group identity, named-group repair, PTY startup, exit codes, diagnostics and the complete Alpine repair toolchain in isolated guests.
