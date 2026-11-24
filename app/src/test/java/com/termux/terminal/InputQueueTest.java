package com.termux.terminal;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class InputQueueTest {
    @Test public void fullQueueRejectsAtomicallyAndAcceptsAfterDraining() {
        InputQueue queue = new InputQueue(8);
        assertTrue(queue.write(new byte[]{1,2,3,4,5,6}, 0, 6));
        assertFalse(queue.write(new byte[]{7,8,9}, 0, 3));
        byte[] result = new byte[8];
        assertEquals(6, queue.read(result, false));
        assertArrayEquals(new byte[]{1,2,3,4,5,6,0,0}, result);
        assertTrue(queue.write(new byte[]{7,8,9}, 0, 3));
        assertEquals(3, queue.read(result, false));
        assertEquals(0, queue.read(result, false));
    }

    @Test public void mutableProducerBuffersCannotCorruptQueuedUtf8OrOrder() {
        InputQueue queue = new InputQueue(100);
        byte[] text = "café 🚀".getBytes(StandardCharsets.UTF_8);
        byte[] expected = text.clone();
        assertTrue(queue.write(text, 0, text.length));
        text[0] = 0;
        assertTrue(queue.write(new byte[]{13}, 0, 1));
        byte[] result = new byte[expected.length + 1];
        assertEquals(result.length, queue.read(result, false));
        assertEquals("café 🚀\r", new String(result, StandardCharsets.UTF_8));
    }

    @Test public void closeWakesConsumerAndDiscardsPendingInput() throws Exception {
        InputQueue queue = new InputQueue(8);
        CountDownLatch finished = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            assertEquals(-1, queue.read(new byte[8], true));
            finished.countDown();
        });
        consumer.start();
        queue.close();
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertFalse(queue.write(new byte[]{1}, 0, 1));
        assertEquals(-1, queue.read(new byte[8], false));
        consumer.join();
    }

    @Test public void partialReadsAcrossPacketsPreserveEveryByte() {
        InputQueue queue = new InputQueue(16);
        queue.write(new byte[]{1,2,3}, 0, 3);
        queue.write(new byte[]{4,5,6}, 0, 3);
        byte[] first = new byte[4];
        assertEquals(4, queue.read(first, false));
        assertArrayEquals(new byte[]{1,2,3,4}, first);
        byte[] second = new byte[4];
        assertEquals(2, queue.read(second, false));
        assertArrayEquals(new byte[]{5,6,0,0}, second);
    }
}
