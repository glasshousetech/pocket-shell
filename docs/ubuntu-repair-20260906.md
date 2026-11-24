# Existing Ubuntu repair on Android 16

A real Fold 7 running Ubuntu 24.04 exposed two independent failures:

1. `dpkg --configure -a` could not resolve missing dependencies after an interrupted upgrade. Refresh sources, run APT fix-broken without named packages and with `--no-remove`, then request the toolchain. This preserves the existing rootfs.
2. `proot-userland` from green-green-avk's v0.15 package reads `statx` pathname from argument 1 in USERLAND fake_id0, where it should read argument 2. This produces EFAULT from `ls` and Node lstat, making npm report its existing launcher as missing. Neither a kernel-release override, disabling link2symlink, nor disabling seccomp fixed it. Disabling seccomp also introduced ENOSYS on this phone.

Use standard `root/bin/proot` from build-proot-android commit `01f83b8841358450c78333d1b33ab30d4943bec4`, preserving its external loaders and `-0`. See THIRD_PARTY.md. The alternative source fix is to include `PR_statx` alongside fstatat/newfstatat in the USERLAND path-argument branch.

Physical-device verification: the added UbuntuRepairDeviceTest failed before the replacement and passed after it. The real terminal then ran Python 3.12.3, Node 18.19.1, npm 9.2.0, Git 2.43.0, and SSH 9.6p1. Existing files/rootfs were preserved. Only arm64 received physical-device execution; the other ABI binaries are from the same upstream package revision.

Run the diagnostic against a release-signed installation with `-PtestBuildType=release` and a test APK signed by the same release key. Do not distribute the test APK to end users. No generic shell-command receiver was added.
