Native fork compatibility backport

Source: termux/proot commits 9bd53079d25737ac739e1210cd490c49377d0cbe and e2d843ee6dab076ea0ed1ffd5d0ea12232185499 (GPL-2.0-or-later). The pinned green-green-avk base registers children synchronously in new_child rather than a separate attach_child; the register restoration is adapted at that same lifecycle point. The upstream fork_regs.c is retained verbatim as a real Android regression probe.
