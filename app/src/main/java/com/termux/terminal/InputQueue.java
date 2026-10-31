package com.termux.terminal;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Pocket Shell: UI-side writes never wait for a subprocess. Single ordered consumer. */
final class InputQueue {
    static final int DEFAULT_CAPACITY = 1024 * 1024;
    private final int capacity;
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private int headOffset;
    private int pendingBytes;
    private boolean open = true;

    InputQueue(int capacity) { this.capacity = capacity; }

    /** Accept the whole write or none of it. Copy buffers reused by writeCodePoint(). */
    synchronized boolean write(byte[] bytes, int offset, int count) {
        if (offset < 0 || count < 0 || offset > bytes.length - count)
            throw new IndexOutOfBoundsException();
        if (!open || count > capacity - pendingBytes || chunks.size() >= 4096) return false;
        if (count == 0) return true;
        chunks.add(Arrays.copyOfRange(bytes, offset, offset + count));
        pendingBytes += count;
        notifyAll();
        return true;
    }

    synchronized int read(byte[] target, boolean block) {
        while (open && chunks.isEmpty()) {
            if (!block) return 0;
            try { wait(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        if (!open) return -1;
        int copied = 0;
        while (copied < target.length && !chunks.isEmpty()) {
            byte[] head = chunks.peek();
            int count = Math.min(target.length - copied, head.length - headOffset);
            System.arraycopy(head, headOffset, target, copied, count);
            copied += count;
            headOffset += count;
            pendingBytes -= count;
            if (headOffset == head.length) {
                chunks.remove();
                headOffset = 0;
            }
        }
        return copied;
    }

    synchronized void close() {
        open = false;
        chunks.clear();
        headOffset = pendingBytes = 0;
        notifyAll();
    }
}
