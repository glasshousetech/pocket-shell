package com.termux.terminal;

import java.util.Arrays;

/**
 * A circular buffer of {@link TerminalRow}:s which keeps notes about what is visible on a logical screen and the scroll
 * history.
 * <p>
 * See {@link #externalToInternalRow(int)} for how to map from logical screen rows to array indices.
 */
public final class TerminalBuffer {

    TerminalRow[] mLines;
    /** The length of {@link #mLines}. */
    int mTotalRows;
    /** The number of rows and columns visible on the screen. */
    int mScreenRows, mColumns;
    /** The number of rows kept in history. */
    private int mActiveTranscriptRows = 0;

    /**
     * Pocket Shell: receives every row exactly once, at the moment it becomes permanent scrollback (rows above the
     * screen can no longer be changed by programs). Lets the app keep complete session history on device storage
     * while only the most recent rows stay in memory.
     */
    public interface HistoryListener {
        /** @param text the row's text; @param wrapsToNext true when the logical line continues on the next row. */
        void onHistoryRow(String text, boolean wrapsToNext);
    }

    private HistoryListener mHistoryListener;
    /**
     * Pocket Shell: the listener already received the top this-many screen rows (they came back from scrollback
     * when the screen grew, e.g. the keyboard closed). They are skipped when they scroll out again, unless written to.
     */
    private int mLoggedScreenRows;
    /** Pocket Shell: true while a column change rebuilds the buffer; rows moving then are not new history. */
    private boolean mReflowing;
    private int mReflowScrolls;
    /** Pocket Shell: style for rows allocated on demand (the current style during a rebuild, as upstream used). */
    private long mAllocationStyle;

    /**
     * Pocket Shell: deliver future scrollback rows to {@code listener}.
     *
     * @param loggedScreenRows how many top screen rows the listener's history already contains.
     */
    public void setHistoryListener(HistoryListener listener, int loggedScreenRows) {
        mHistoryListener = listener;
        mLoggedScreenRows = Math.max(0, Math.min(loggedScreenRows, mScreenRows));
    }

    /** Pocket Shell: top screen rows the history listener already has. */
    public int getLoggedScreenRows() {
        return mLoggedScreenRows;
    }

    private void emitHistoryRow(int externalRow) {
        TerminalRow row = mLines[externalToInternalRow(externalRow)];
        if (row == null) {
            mHistoryListener.onHistoryRow("", false);
            return;
        }
        mHistoryListener.onHistoryRow(getSelectedText(0, externalRow, mColumns, externalRow, true, false), row.mLineWrap);
    }

    /** Pocket Shell: a screen row's content changed, so it and every row below it must be logged when they leave. */
    private void noteRowModified(int externalRow) {
        if (externalRow < mLoggedScreenRows && !mReflowing) mLoggedScreenRows = Math.max(0, externalRow);
    }
    /** The index in the circular buffer where the visible screen starts. */
    private int mScreenFirstRow = 0;

    /**
     * Create a transcript screen.
     *
     * @param columns    the width of the screen in characters.
     * @param totalRows  the height of the entire text area, in rows of text.
     * @param screenRows the height of just the screen, not including the transcript that holds lines that have scrolled off
     *                   the top of the screen.
     */
    public TerminalBuffer(int columns, int totalRows, int screenRows) {
        mColumns = columns;
        mTotalRows = totalRows;
        mScreenRows = screenRows;
        mLines = new TerminalRow[totalRows];

        blockSet(0, 0, columns, screenRows, ' ', TextStyle.NORMAL);
    }

    public String getTranscriptText() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows).trim();
    }

    public String getTranscriptTextWithoutJoinedLines() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, false).trim();
    }

    public String getTranscriptTextWithFullLinesJoined() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, true, true).trim();
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2) {
        return getSelectedText(selX1, selY1, selX2, selY2, true);
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2, boolean joinBackLines) {
        return getSelectedText(selX1, selY1, selX2, selY2, true, false);
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2, boolean joinBackLines, boolean joinFullLines) {
        final StringBuilder builder = new StringBuilder();
        final int columns = mColumns;

        if (selY1 < -getActiveTranscriptRows()) selY1 = -getActiveTranscriptRows();
        if (selY2 >= mScreenRows) selY2 = mScreenRows - 1;

        for (int row = selY1; row <= selY2; row++) {
            int x1 = (row == selY1) ? selX1 : 0;
            int x2;
            if (row == selY2) {
                x2 = selX2 + 1;
                if (x2 > columns) x2 = columns;
            } else {
                x2 = columns;
            }
            TerminalRow lineObject = mLines[externalToInternalRow(row)];
            if (lineObject == null) {
                // Pocket Shell: rows are allocated on demand; an unallocated row is blank.
                if (row < selY2 && row < mScreenRows - 1) builder.append('\n');
                continue;
            }
            int x1Index = lineObject.findStartOfColumn(x1);
            int x2Index = (x2 < mColumns) ? lineObject.findStartOfColumn(x2) : lineObject.getSpaceUsed();
            if (x2Index == x1Index) {
                // Selected the start of a wide character.
                x2Index = lineObject.findStartOfColumn(x2 + 1);
            }
            char[] line = lineObject.mText;
            int lastPrintingCharIndex = -1;
            int i;
            boolean rowLineWrap = getLineWrap(row);
            if (rowLineWrap && x2 == columns) {
                // If the line was wrapped, we shouldn't lose trailing space:
                lastPrintingCharIndex = x2Index - 1;
            } else {
                for (i = x1Index; i < x2Index; ++i) {
                    char c = line[i];
                    if (c != ' ') lastPrintingCharIndex = i;
                }
            }
            if (lastPrintingCharIndex != -1)
                builder.append(line, x1Index, lastPrintingCharIndex - x1Index + 1);
            boolean lineFillsWidth = lastPrintingCharIndex == x2Index - 1;
            if ((!joinBackLines || !rowLineWrap) && (!joinFullLines || !lineFillsWidth)
                && row < selY2 && row < mScreenRows - 1) builder.append('\n');
        }
        return builder.toString();
    }

    public String getWordAtLocation(int x, int y) {
        // Set y1 and y2 to the lines where the wrapped line starts and ends.
        // I.e. if a line that is wrapped to 3 lines starts at line 4, and this
        // is called with y=5, then y1 would be set to 4 and y2 would be set to 6.
        int y1 = y;
        int y2 = y;
        while (y1 > 0 && !getSelectedText(0, y1 - 1, mColumns, y, true, true).contains("\n")) {
            y1--;
        }
        while (y2 < mScreenRows && !getSelectedText(0, y, mColumns, y2 + 1, true, true).contains("\n")) {
            y2++;
        }

        // Get the text for the whole wrapped line
        String text = getSelectedText(0, y1, mColumns, y2, true, true);
        // The index of x in text
        int textOffset = (y - y1) * mColumns + x;

        if (textOffset >= text.length()) {
          // The click was to the right of the last word on the line, so
          // there's no word to return
          return "";
        }

        // Set x1 and x2 to the indices of the last space before x and the
        // first space after x in text respectively
        int x1 = text.lastIndexOf(' ', textOffset);
        int x2 = text.indexOf(' ', textOffset);
        if (x2 == -1) {
            x2 = text.length();
        }

        if (x1 == x2) {
          // The click was on a space, so there's no word to return
          return "";
        }
        return text.substring(x1 + 1, x2);
    }

    public int getActiveTranscriptRows() {
        return mActiveTranscriptRows;
    }

    public int getActiveRows() {
        return mActiveTranscriptRows + mScreenRows;
    }

    /**
     * Convert a row value from the public external coordinate system to our internal private coordinate system.
     *
     * <pre>
     * - External coordinate system: -mActiveTranscriptRows to mScreenRows-1, with the screen being 0..mScreenRows-1.
     * - Internal coordinate system: the mScreenRows lines starting at mScreenFirstRow comprise the screen, while the
     *   mActiveTranscriptRows lines ending at mScreenFirstRow-1 form the transcript (as a circular buffer).
     *
     * External ↔ Internal:
     *
     * [ ...                            ]     [ ...                                     ]
     * [ -mActiveTranscriptRows         ]     [ mScreenFirstRow - mActiveTranscriptRows ]
     * [ ...                            ]     [ ...                                     ]
     * [ 0 (visible screen starts here) ]  ↔  [ mScreenFirstRow                         ]
     * [ ...                            ]     [ ...                                     ]
     * [ mScreenRows-1                  ]     [ mScreenFirstRow + mScreenRows-1         ]
     * </pre>
     *
     * @param externalRow a row in the external coordinate system.
     * @return The row corresponding to the input argument in the private coordinate system.
     */
    public int externalToInternalRow(int externalRow) {
        if (externalRow < -mActiveTranscriptRows || externalRow > mScreenRows)
            throw new IllegalArgumentException("extRow=" + externalRow + ", mScreenRows=" + mScreenRows + ", mActiveTranscriptRows=" + mActiveTranscriptRows);
        final int internalRow = mScreenFirstRow + externalRow;
        return (internalRow < 0) ? (mTotalRows + internalRow) : (internalRow % mTotalRows);
    }

    public void setLineWrap(int row) {
        allocateFullLineIfNecessary(externalToInternalRow(row)).mLineWrap = true;
    }

    public boolean getLineWrap(int row) {
        TerminalRow line = mLines[externalToInternalRow(row)];
        return line != null && line.mLineWrap;
    }

    public void clearLineWrap(int row) {
        TerminalRow line = mLines[externalToInternalRow(row)];
        if (line != null) line.mLineWrap = false;
    }

    /**
     * Resize the screen which this transcript backs. Currently, this only works if the number of columns does not
     * change or the rows expand (that is, it only works when shrinking the number of rows).
     *
     * @param newColumns The number of columns the screen should have.
     * @param newRows    The number of rows the screen should have.
     * @param cursor     An int[2] containing the (column, row) cursor location.
     */
    public void resize(int newColumns, int newRows, int newTotalRows, int[] cursor, long currentStyle, boolean altScreen) {
        // newRows > mTotalRows should not normally happen since mTotalRows is TRANSCRIPT_ROWS (10000):
        if (newColumns == mColumns && newRows <= mTotalRows) {
            // Fast resize where just the rows changed.
            int shiftDownOfTopRow = mScreenRows - newRows;
            if (shiftDownOfTopRow > 0 && shiftDownOfTopRow < mScreenRows) {
                // Shrinking. Check if we can skip blank rows at bottom below cursor.
                for (int i = mScreenRows - 1; i > 0; i--) {
                    if (cursor[1] >= i) break;
                    int r = externalToInternalRow(i);
                    if (mLines[r] == null || mLines[r].isBlank()) {
                        if (--shiftDownOfTopRow == 0) break;
                    }
                }
            } else if (shiftDownOfTopRow < 0) {
                // Negative shift down = expanding. Only move screen up if there is transcript to show:
                int actualShift = Math.max(shiftDownOfTopRow, -mActiveTranscriptRows);
                if (shiftDownOfTopRow != actualShift) {
                    // The new lines revealed by the resizing are not all from the transcript. Blank the below ones.
                    for (int i = 0; i < actualShift - shiftDownOfTopRow; i++)
                        allocateFullLineIfNecessary((mScreenFirstRow + mScreenRows + i) % mTotalRows).clear(currentStyle);
                    shiftDownOfTopRow = actualShift;
                }
            }
            if (mHistoryListener != null && !altScreen) {
                if (shiftDownOfTopRow > 0) {
                    // Pocket Shell: the top rows become scrollback without scrolling. Log those not logged yet.
                    for (int i = 0; i < shiftDownOfTopRow; i++) {
                        if (mLoggedScreenRows > 0) mLoggedScreenRows--;
                        else emitHistoryRow(i);
                    }
                } else if (shiftDownOfTopRow < 0) {
                    // Rows returning from scrollback were already logged.
                    mLoggedScreenRows = Math.min(newRows, mLoggedScreenRows - shiftDownOfTopRow);
                }
            }
            mScreenFirstRow += shiftDownOfTopRow;
            mScreenFirstRow = (mScreenFirstRow < 0) ? (mScreenFirstRow + mTotalRows) : (mScreenFirstRow % mTotalRows);
            mTotalRows = newTotalRows;
            mActiveTranscriptRows = altScreen ? 0 : Math.max(0, mActiveTranscriptRows + shiftDownOfTopRow);
            cursor[1] -= shiftDownOfTopRow;
            mScreenRows = newRows;
        } else {
            // Copy away old state and update new:
            TerminalRow[] oldLines = mLines;
            // Pocket Shell: upstream allocated all newTotalRows rows here (20,000 rows, ~25 MB at Fold widths) on
            // every column change. Rows are allocated as they are used; see the end of this branch.
            mLines = new TerminalRow[newTotalRows];
            final boolean trackHistory = mHistoryListener != null && !altScreen;
            final int firstUnloggedOldRow = mLoggedScreenRows;
            int unloggedMarkerRow = Integer.MIN_VALUE;
            int unloggedMarkerScrolls = 0;
            mReflowing = true;
            mReflowScrolls = 0;
            // Rows created during the rebuild get the same style upstream's eager allocation gave them.
            mAllocationStyle = currentStyle;

            final int oldActiveTranscriptRows = mActiveTranscriptRows;
            final int oldScreenFirstRow = mScreenFirstRow;
            final int oldScreenRows = mScreenRows;
            final int oldTotalRows = mTotalRows;
            mTotalRows = newTotalRows;
            mScreenRows = newRows;
            mActiveTranscriptRows = mScreenFirstRow = 0;
            mColumns = newColumns;

            int newCursorRow = -1;
            int newCursorColumn = -1;
            int oldCursorRow = cursor[1];
            int oldCursorColumn = cursor[0];
            boolean newCursorPlaced = false;

            int currentOutputExternalRow = 0;
            int currentOutputExternalColumn = 0;

            // Loop over every character in the initial state.
            // Blank lines should be skipped only if at end of transcript (just as is done in the "fast" resize), so we
            // keep track how many blank lines we have skipped if we later on find a non-blank line.
            int skippedBlankLines = 0;
            for (int externalOldRow = -oldActiveTranscriptRows; externalOldRow < oldScreenRows; externalOldRow++) {
                // Do what externalToInternalRow() does but for the old state:
                int internalOldRow = oldScreenFirstRow + externalOldRow;
                internalOldRow = (internalOldRow < 0) ? (oldTotalRows + internalOldRow) : (internalOldRow % oldTotalRows);

                TerminalRow oldLine = oldLines[internalOldRow];
                boolean cursorAtThisRow = externalOldRow == oldCursorRow;
                // The cursor may only be on a non-null line, which we should not skip:
                if (oldLine == null || (!(!newCursorPlaced && cursorAtThisRow)) && oldLine.isBlank()) {
                    skippedBlankLines++;
                    continue;
                } else if (skippedBlankLines > 0) {
                    // After skipping some blank lines we encounter a non-blank line. Insert the skipped blank lines.
                    for (int i = 0; i < skippedBlankLines; i++) {
                        if (currentOutputExternalRow == mScreenRows - 1) {
                            scrollDownOneLine(0, mScreenRows, currentStyle);
                        } else {
                            currentOutputExternalRow++;
                        }
                        currentOutputExternalColumn = 0;
                    }
                    skippedBlankLines = 0;
                }

                if (trackHistory && unloggedMarkerRow == Integer.MIN_VALUE && externalOldRow >= firstUnloggedOldRow) {
                    // Pocket Shell: first content the history listener has not received yet.
                    unloggedMarkerRow = currentOutputExternalRow;
                    unloggedMarkerScrolls = mReflowScrolls;
                }

                int lastNonSpaceIndex = 0;
                boolean justToCursor = false;
                if (cursorAtThisRow || oldLine.mLineWrap) {
                    // Take the whole line, either because of cursor on it, or if line wrapping.
                    lastNonSpaceIndex = oldLine.getSpaceUsed();
                    if (cursorAtThisRow) justToCursor = true;
                } else {
                    for (int i = 0; i < oldLine.getSpaceUsed(); i++)
                        // NEWLY INTRODUCED BUG! Should not index oldLine.mStyle with char indices
                        if (oldLine.mText[i] != ' '/* || oldLine.mStyle[i] != currentStyle */)
                            lastNonSpaceIndex = i + 1;
                }

                int currentOldCol = 0;
                long styleAtCol = 0;
                for (int i = 0; i < lastNonSpaceIndex; i++) {
                    // Note that looping over java character, not cells.
                    char c = oldLine.mText[i];
                    int codePoint = (Character.isHighSurrogate(c)) ? Character.toCodePoint(c, oldLine.mText[++i]) : c;
                    int displayWidth = WcWidth.width(codePoint);
                    // Use the last style if this is a zero-width character:
                    if (displayWidth > 0) styleAtCol = oldLine.getStyle(currentOldCol);

                    // Line wrap as necessary:
                    if (currentOutputExternalColumn + displayWidth > mColumns) {
                        setLineWrap(currentOutputExternalRow);
                        if (currentOutputExternalRow == mScreenRows - 1) {
                            if (newCursorPlaced) newCursorRow--;
                            scrollDownOneLine(0, mScreenRows, currentStyle);
                        } else {
                            currentOutputExternalRow++;
                        }
                        currentOutputExternalColumn = 0;
                    }

                    int offsetDueToCombiningChar = ((displayWidth <= 0 && currentOutputExternalColumn > 0) ? 1 : 0);
                    int outputColumn = currentOutputExternalColumn - offsetDueToCombiningChar;
                    setChar(outputColumn, currentOutputExternalRow, codePoint, styleAtCol);

                    if (displayWidth > 0) {
                        if (oldCursorRow == externalOldRow && oldCursorColumn == currentOldCol) {
                            newCursorColumn = currentOutputExternalColumn;
                            newCursorRow = currentOutputExternalRow;
                            newCursorPlaced = true;
                        }
                        currentOldCol += displayWidth;
                        currentOutputExternalColumn += displayWidth;
                        if (justToCursor && newCursorPlaced) break;
                    }
                }
                // Old row has been copied. Check if we need to insert newline if old line was not wrapping:
                if (externalOldRow != (oldScreenRows - 1) && !oldLine.mLineWrap) {
                    if (currentOutputExternalRow == mScreenRows - 1) {
                        if (newCursorPlaced) newCursorRow--;
                        scrollDownOneLine(0, mScreenRows, currentStyle);
                    } else {
                        currentOutputExternalRow++;
                    }
                    currentOutputExternalColumn = 0;
                }
            }

            cursor[0] = newCursorColumn;
            cursor[1] = newCursorRow;

            // Pocket Shell: keep the invariant that every active row exists, without allocating unused capacity.
            for (int row = -mActiveTranscriptRows; row < mScreenRows; row++)
                allocateFullLineIfNecessary(externalToInternalRow(row));
            mReflowing = false;
            mAllocationStyle = 0;
            if (trackHistory) {
                if (unloggedMarkerRow == Integer.MIN_VALUE) {
                    // Everything before the cursor was already history; the cursor row onward is new.
                    mLoggedScreenRows = Math.max(0, Math.min(newCursorRow, mScreenRows));
                } else {
                    int markerNow = unloggedMarkerRow - (mReflowScrolls - unloggedMarkerScrolls);
                    if (markerNow >= 0) {
                        mLoggedScreenRows = markerNow;
                    } else {
                        // Unlogged rows were pushed into scrollback by the reflow: deliver them now, in order.
                        for (int row = Math.max(markerNow, -mActiveTranscriptRows); row < 0; row++) emitHistoryRow(row);
                        mLoggedScreenRows = 0;
                    }
                }
            }
        }

        // Handle cursor scrolling off screen:
        if (cursor[0] < 0 || cursor[1] < 0) cursor[0] = cursor[1] = 0;
    }

    /**
     * Block copy lines and associated metadata from one location to another in the circular buffer, taking wraparound
     * into account.
     *
     * @param srcInternal The first line to be copied.
     * @param len         The number of lines to be copied.
     */
    private void blockCopyLinesDown(int srcInternal, int len) {
        if (len == 0) return;
        int totalRows = mTotalRows;

        int start = len - 1;
        // Save away line to be overwritten:
        TerminalRow lineToBeOverWritten = mLines[(srcInternal + start + 1) % totalRows];
        // Do the copy from bottom to top.
        for (int i = start; i >= 0; --i)
            mLines[(srcInternal + i + 1) % totalRows] = mLines[(srcInternal + i) % totalRows];
        // Put back overwritten line, now above the block:
        mLines[(srcInternal) % totalRows] = lineToBeOverWritten;
    }

    /**
     * Scroll the screen down one line. To scroll the whole screen of a 24 line screen, the arguments would be (0, 24).
     *
     * @param topMargin    First line that is scrolled.
     * @param bottomMargin One line after the last line that is scrolled.
     * @param style        the style for the newly exposed line.
     */
    public void scrollDownOneLine(int topMargin, int bottomMargin, long style) {
        if (topMargin > bottomMargin - 1 || topMargin < 0 || bottomMargin > mScreenRows)
            throw new IllegalArgumentException("topMargin=" + topMargin + ", bottomMargin=" + bottomMargin + ", mScreenRows=" + mScreenRows);

        // Copy the fixed topMargin lines one line down so that they remain on screen in same position:
        blockCopyLinesDown(mScreenFirstRow, topMargin);
        // Copy the fixed mScreenRows-bottomMargin lines one line down so that they remain on screen in same
        // position:
        blockCopyLinesDown(externalToInternalRow(bottomMargin), mScreenRows - bottomMargin);

        // Update the screen location in the ring buffer:
        mScreenFirstRow = (mScreenFirstRow + 1) % mTotalRows;
        // Note that the history has grown if not already full:
        if (mActiveTranscriptRows < mTotalRows - mScreenRows) mActiveTranscriptRows++;

        if (mReflowing) {
            mReflowScrolls++;
        } else if (mHistoryListener != null && mTotalRows > mScreenRows) {
            // Pocket Shell: external row -1 is the row that just left the screen (see blockCopyLinesDown).
            if (topMargin == 0 && mLoggedScreenRows > 0) mLoggedScreenRows--;
            else emitHistoryRow(-1);
        }

        // Blank the newly revealed line above the bottom margin:
        int blankRow = externalToInternalRow(bottomMargin - 1);
        if (mLines[blankRow] == null) {
            mLines[blankRow] = new TerminalRow(mColumns, style);
        } else {
            mLines[blankRow].clear(style);
        }
    }

    /**
     * Block copy characters from one position in the screen to another. The two positions can overlap. All characters
     * of the source and destination must be within the bounds of the screen, or else an InvalidParameterException will
     * be thrown.
     *
     * @param sx source X coordinate
     * @param sy source Y coordinate
     * @param w  width
     * @param h  height
     * @param dx destination X coordinate
     * @param dy destination Y coordinate
     */
    public void blockCopy(int sx, int sy, int w, int h, int dx, int dy) {
        if (w == 0) return;
        noteRowModified(dy);
        if (sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows || dx < 0 || dx + w > mColumns || dy < 0 || dy + h > mScreenRows)
            throw new IllegalArgumentException();
        boolean copyingUp = sy > dy;
        for (int y = 0; y < h; y++) {
            int y2 = copyingUp ? y : (h - (y + 1));
            TerminalRow sourceRow = allocateFullLineIfNecessary(externalToInternalRow(sy + y2));
            allocateFullLineIfNecessary(externalToInternalRow(dy + y2)).copyInterval(sourceRow, sx, sx + w, dx);
        }
    }

    /**
     * Block set characters. All characters must be within the bounds of the screen, or else and
     * InvalidParemeterException will be thrown. Typically this is called with a "val" argument of 32 to clear a block
     * of characters.
     */
    public void blockSet(int sx, int sy, int w, int h, int val, long style) {
        noteRowModified(sy);
        if (sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows) {
            throw new IllegalArgumentException(
                "Illegal arguments! blockSet(" + sx + ", " + sy + ", " + w + ", " + h + ", " + val + ", " + mColumns + ", " + mScreenRows + ")");
        }
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                setChar(sx + x, sy + y, val, style);
    }

    public TerminalRow allocateFullLineIfNecessary(int row) {
        return (mLines[row] == null) ? (mLines[row] = new TerminalRow(mColumns, mAllocationStyle)) : mLines[row];
    }

    public void setChar(int column, int row, int codePoint, long style) {
        if (row >= mScreenRows || column >= mColumns)
            throw new IllegalArgumentException("row=" + row + ", column=" + column + ", mScreenRows=" + mScreenRows + ", mColumns=" + mColumns);
        if (row < mLoggedScreenRows) noteRowModified(row);
        row = externalToInternalRow(row);
        allocateFullLineIfNecessary(row).setChar(column, codePoint, style);
    }

    public long getStyleAt(int externalRow, int column) {
        return allocateFullLineIfNecessary(externalToInternalRow(externalRow)).getStyle(column);
    }

    /** Support for http://vt100.net/docs/vt510-rm/DECCARA and http://vt100.net/docs/vt510-rm/DECCARA */
    public void setOrClearEffect(int bits, boolean setOrClear, boolean reverse, boolean rectangular, int leftMargin, int rightMargin, int top, int left,
                                 int bottom, int right) {
        noteRowModified(top);
        for (int y = top; y < bottom; y++) {
            TerminalRow line = allocateFullLineIfNecessary(externalToInternalRow(y));
            int startOfLine = (rectangular || y == top) ? left : leftMargin;
            int endOfLine = (rectangular || y + 1 == bottom) ? right : rightMargin;
            for (int x = startOfLine; x < endOfLine; x++) {
                long currentStyle = line.getStyle(x);
                int foreColor = TextStyle.decodeForeColor(currentStyle);
                int backColor = TextStyle.decodeBackColor(currentStyle);
                int effect = TextStyle.decodeEffect(currentStyle);
                if (reverse) {
                    // Clear out the bits to reverse and add them back in reversed:
                    effect = (effect & ~bits) | (bits & ~effect);
                } else if (setOrClear) {
                    effect |= bits;
                } else {
                    effect &= ~bits;
                }
                line.mStyle[x] = TextStyle.encode(foreColor, backColor, effect);
            }
        }
    }

    public void clearTranscript() {
        if (mScreenFirstRow < mActiveTranscriptRows) {
            Arrays.fill(mLines, mTotalRows + mScreenFirstRow - mActiveTranscriptRows, mTotalRows, null);
            Arrays.fill(mLines, 0, mScreenFirstRow, null);
        } else {
            Arrays.fill(mLines, mScreenFirstRow - mActiveTranscriptRows, mScreenFirstRow, null);
        }
        mActiveTranscriptRows = 0;
    }

}
