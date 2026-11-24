package com.termux.terminal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pocket Shell: every row reaches the complete-history log exactly once, through scrolling and every resize. */
public class HistoryListenerTest extends TerminalTestCase {

	private final StringBuilder log = new StringBuilder();

	private void record(int loggedRows) {
		mTerminal.setHistoryListener((text, wrapsToNext) -> {
			log.append(text);
			if (!wrapsToNext) log.append('\n');
		}, loggedRows);
	}

	private void assertLogged(String... lines) {
		List<String> expected = new ArrayList<>(Arrays.asList(lines));
		String text = log.toString();
		List<String> actual = text.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(text.split("\n", -1)));
		if (!actual.isEmpty() && actual.get(actual.size() - 1).isEmpty()) actual.remove(actual.size() - 1);
		assertEquals(expected, actual);
	}

	public void testRowsAreLoggedWhenTheyScrollIntoHistory() {
		withTerminalSized(3, 3);
		record(0);
		enterString("a\r\nb\r\nc\r\nd\r\ne");
		assertLinesAre("c  ", "d  ", "e  ");
		assertLogged("a", "b");
	}

	public void testKeyboardShrinkAndExpandLogEachRowOnce() {
		withTerminalSized(3, 3);
		record(0);
		enterString("a\r\nb\r\nc\r\nd\r\ne");
		resize(3, 2); // keyboard opens: "c" moves into history without scrolling
		assertLogged("a", "b", "c");
		resize(3, 3); // keyboard closes: "c" returns to the screen
		assertLinesAre("c  ", "d  ", "e  ");
		enterString("\r\nf"); // "c" leaves again: already logged
		assertLogged("a", "b", "c");
		enterString("\r\ng");
		assertLogged("a", "b", "c", "d");
	}

	public void testRowRewrittenAfterReturningFromHistoryIsLoggedAgain() {
		withTerminalSized(3, 3);
		record(0);
		enterString("a\r\nb\r\nc\r\nd\r\ne");
		resize(3, 2);
		resize(3, 3);
		enterString("\033[H\033[2Jx\r\ny\r\nz\r\nw"); // clear screen, new content, then scroll
		assertLogged("a", "b", "c", "x");
	}

	public void testWrappedRowsFormOneLogicalLine() {
		withTerminalSized(3, 3);
		record(0);
		enterString("abcdefg\r\nx\r\ny\r\nz");
		assertLogged("abcdefg");
	}

	public void testColumnReflowDoesNotDuplicateHistory() {
		withTerminalSized(4, 3);
		record(0);
		enterString("aaaa\r\nbb\r\ncc\r\ndd\r\ne");
		assertLogged("aaaa", "bb");
		resize(2, 3); // fold or unfold: the whole buffer is rebuilt
		assertLogged("aaaa", "bb");
		enterString("\r\nff");
		assertLogged("aaaa", "bb", "cc");
	}

	public void testColumnReflowLogsRowsItPushesIntoHistory() {
		withTerminalSized(4, 3);
		record(0);
		enterString("aaaa\r\nbbbb\r\ncccc");
		assertLogged();
		resize(2, 3); // six narrow rows; the top three become history during the rebuild
		enterString("\r\nzz");
		assertLogged("aaaa", "bbbb");
	}

	public void testAlternateScreenIsNotLogged() {
		withTerminalSized(3, 3);
		record(0);
		enterString("a\r\nb\r\nc\r\nd");
		assertLogged("a");
		enterString("\033[?1049h1\r\n2\r\n3\r\n4\r\n5\033[?1049l");
		assertLogged("a");
		enterString("\r\ne");
		assertLogged("a", "b");
	}

	public void testRestoredScreenRowsAreNotLoggedTwice() {
		withTerminalSized(3, 3);
		enterString("old\r\nrow\r\n"); // replayed history; cursor now on the third row
		record(mTerminal.getCursorRow());
		enterString("new\r\nx\r\ny\r\nz");
		assertLogged("new");
	}
}
