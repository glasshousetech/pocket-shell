Native fork compatibility backport

Source: termux/proot commits 9bd53079d25737ac739e1210cd490c49377d0cbe and e2d843ee6dab076ea0ed1ffd5d0ea12232185499 (GPL-2.0-or-later). The pinned green-green-avk base registers children synchronously in new_child rather than a separate attach_child; the register restoration is adapted at that same lifecycle point. The upstream fork_regs.c is retained verbatim as a real Android regression probe.

Native select compatibility

The select-to-pselect6 translation follows termux/proot 52d65a4dc140ca554d241a1438ddd4f6b00c9813 src/tracee/seccomp.c. PocketShell additionally preserves Linux timeout write-back, validates negative/invalid arguments and normalizes microseconds. Source: https://github.com/termux/proot/blob/52d65a4dc140ca554d241a1438ddd4f6b00c9813/src/tracee/seccomp.c ; Linux behavior reference: https://github.com/torvalds/linux/blob/v6.1/fs/select.c . This candidate rebuilds only x86_64; ARM binaries are unchanged.

The direct syscall probe is built with gcc -static -O2 -Wall -Wextra -o runtime-syscalls-x86_64 runtime-syscalls.c. It checks select readiness, fd-set clearing, finite/infinite timeout, remaining-time update, signal interruption, input validation and errno alongside the earlier filesystem/descriptor cases. A passing host control is not Android evidence; actual app-context tests are recorded separately.
