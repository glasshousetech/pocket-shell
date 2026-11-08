package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;
    private static final int MSG_OUTPUT_DRAINED = 5;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(4096);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the {@link #mTerminalFileDescriptor}.
     */
    // Pocket Shell: backpressure must never block input or incoming emulation on the main looper.
    final InputQueue mTerminalToProcessIOQueue = new InputQueue(InputQueue.DEFAULT_CAPACITY);
    private ByteArrayOutputStream mInputBatch;
    private int mInputBatchDepth;
    private boolean mInputBatchRejected;
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    int mShellPid;

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /**
     * The file descriptor referencing the master half of a pseudo-terminal pair, resulting from calling
     * {@link JNI#createSubprocess(String, String, String[], String[], int[], int, int)}.
     */
    private int mTerminalFileDescriptor;
    private FileDescriptor mTerminalDescriptor;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;


    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows);
        } else {
            if (mTerminalFileDescriptor >= 0) JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns);
            mEmulator.resize(columns, rows);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows) {
        mEmulator = new TerminalEmulator(this, columns, rows, mTranscriptRows, mClient);

        int[] processId = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns);
        mShellPid = processId[0];

        final FileDescriptor terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor, mClient);
        mTerminalDescriptor = terminalFileDescriptorWrapped;
        final FileDescriptor[] exitSignal;
        try {
            exitSignal = Os.pipe();
        } catch (ErrnoException failure) {
            finishIfRunning();
            final int failedPid = mShellPid;
            new Thread(() -> JNI.waitFor(failedPid), "TermSessionFailedStartReaper").start();
            JNI.close(mTerminalFileDescriptor);
            mTerminalFileDescriptor = -1;
            mShellPid = -1;
            throw new IllegalStateException("Could not create terminal exit signal", failure);
        }

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                StructPollfd terminal = new StructPollfd();
                terminal.fd = terminalFileDescriptorWrapped;
                terminal.events = (short) OsConstants.POLLIN;
                StructPollfd exited = new StructPollfd();
                exited.fd = exitSignal[0];
                exited.events = (short) OsConstants.POLLIN;
                final StructPollfd[] descriptors = { terminal, exited };
                boolean draining = false;
                try {
                    while (true) {
                        try {
                            // The waiter wakes this poll when the shell exits. Drain bytes already
                            // in the PTY without waiting for a descendant that keeps its slave open.
                            Os.poll(descriptors, draining ? 0 : -1);
                        } catch (ErrnoException failure) {
                            if (failure.errno == OsConstants.EINTR) continue;
                            throw failure;
                        }
                        if (exited.revents != 0) draining = true;
                        if ((terminal.revents & (OsConstants.POLLIN | OsConstants.POLLHUP)) == 0) {
                            if (draining || terminal.revents != 0) break;
                            continue;
                        }
                        int read;
                        try {
                            read = Os.read(terminalFileDescriptorWrapped, buffer, 0, buffer.length);
                        } catch (ErrnoException failure) {
                            if (failure.errno == OsConstants.EINTR) continue;
                            // Linux PTY masters return EIO when the last slave has closed.
                            if (failure.errno == OsConstants.EIO) break;
                            throw failure;
                        }
                        if (read <= 0 || !mProcessToTerminalIOQueue.write(buffer, 0, read)) break;
                        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }
                } catch (Exception failure) {
                    mClient.logWarn(LOG_TAG, "Terminal output read failed: " + failure.getMessage());
                } finally {
                    try { Os.close(exitSignal[0]); } catch (ErrnoException ignored) { }
                    mMainThreadHandler.sendEmptyMessage(MSG_OUTPUT_DRAINED);
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (true) {
                        int count = mTerminalToProcessIOQueue.read(buffer, true);
                        if (count == -1) return;
                        int offset = 0;
                        while (offset < count) {
                            try {
                                offset += Os.write(terminalFileDescriptorWrapped, buffer, offset, count - offset);
                            } catch (ErrnoException failure) {
                                if (failure.errno != OsConstants.EINTR) throw failure;
                            }
                        }
                    }
                } catch (Exception failure) {
                    if (isRunning()) mClient.logWarn(LOG_TAG, "Terminal input write failed: " + failure.getMessage());
                }
            }
        }.start();

        new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                int processExitCode = JNI.waitFor(mShellPid);
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
                try {
                    while (true) {
                        try {
                            Os.write(exitSignal[1], new byte[] { 1 }, 0, 1);
                            break;
                        } catch (ErrnoException failure) {
                            if (failure.errno != OsConstants.EINTR) throw failure;
                        }
                    }
                } catch (ErrnoException failure) {
                    // EPIPE means the reader already saw EOF and posted OUTPUT_DRAINED.
                    if (failure.errno != OsConstants.EPIPE)
                        mClient.logWarn(LOG_TAG, "Terminal exit signal failed: " + failure.getMessage());
                } catch (IOException failure) {
                    mClient.logWarn(LOG_TAG, "Terminal exit signal interrupted: " + failure.getMessage());
                } finally {
                    try { Os.close(exitSignal[1]); } catch (ErrnoException ignored) { }
                }
            }
        }.start();

    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mInputBatch != null) {
            if (count > InputQueue.DEFAULT_CAPACITY - mInputBatch.size()) mInputBatchRejected = true;
            if (!mInputBatchRejected) mInputBatch.write(data, offset, count);
        } else if (mShellPid <= 0 || !mTerminalToProcessIOQueue.write(data, offset, count)) {
            mClient.onInputRejected(this);
        }
    }

    /** Main-thread batch: transforms keys normally, but enqueues one phrase atomically. */
    public void beginInputBatch() {
        if (mInputBatchDepth++ == 0) {
            mInputBatch = new ByteArrayOutputStream();
            mInputBatchRejected = false;
        }
    }

    public boolean endInputBatch() {
        if (mInputBatchDepth == 0) throw new IllegalStateException("No input batch");
        if (--mInputBatchDepth > 0) return !mInputBatchRejected;
        byte[] bytes = mInputBatch.toByteArray();
        mInputBatch = null;
        boolean accepted = !mInputBatchRejected && mShellPid > 0
            && mTerminalToProcessIOQueue.write(bytes, 0, bytes.length);
        if (!accepted) mClient.onInputRejected(this);
        return accepted;
    }

    /** Allows a reviewed text editor to retain its draft if the session cannot accept it. */
    public boolean tryPaste(String text) {
        if (mEmulator == null) return false;
        beginInputBatch();
        mEmulator.paste(text);
        return endInputBatch();
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    public void finishIfRunning() {
        if (isRunning()) {
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                mClient.logWarn(LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        }
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }

        // Stop the reader and writer threads, and close the I/O streams
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        try { Os.close(mTerminalDescriptor); } catch (ErrnoException failure) {
            mClient.logWarn(LOG_TAG, "Terminal close failed: " + failure.getMessage());
        }
        mTerminalFileDescriptor = -1;
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor, TerminalSessionClient client) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            client.logStackTraceWithMessage(LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e);
            System.exit(1);
        }
        return result;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[4 * 1024];
        private boolean processExited;
        private boolean outputDrained;
        private int pendingExitCode;

        @Override
        public void handleMessage(Message msg) {
            if (mShellPid == -1) return;
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                processExited = true;
                pendingExitCode = (Integer) msg.obj;
            } else if (msg.what == MSG_OUTPUT_DRAINED) {
                outputDrained = true;
            }
            if (processExited && outputDrained) {
                // No producer remains. Render every retained byte before the exit marker and
                // completion callback, even when waitpid beat the reader/main-thread messages.
                while ((bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false)) > 0)
                    mEmulator.append(mReceiveBuffer, bytesRead);
                int exitCode = pendingExitCode;
                cleanupResources(exitCode);

                String exitDescription = "\r\n[Process completed";
                if (exitCode > 0) {
                    // Non-zero process exit.
                    exitDescription += " (code " + exitCode + ")";
                } else if (exitCode < 0) {
                    // Negated signal.
                    exitDescription += " (signal " + (-exitCode) + ")";
                }
                exitDescription += " - press Enter]";

                byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
