package dev.dworks.apps.anexplorer.misc;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;

/** lseek, which Android has offered as android.system.Os since 5.0 */
public final class OsCompat {
    public static final int SEEK_CUR = OsConstants.SEEK_CUR;
    public static final int SEEK_END = OsConstants.SEEK_END;
    public static final int SEEK_SET = OsConstants.SEEK_SET;

    public static class ExecutionFailedException extends Exception {
        public ExecutionFailedException(String detailMessage) {
            super(detailMessage);
        }

        public ExecutionFailedException(String detailMessage, Throwable throwable) {
            super(detailMessage, throwable);
        }

        public ExecutionFailedException(Throwable throwable) {
            super(throwable);
        }
    }

    public static long lseek(FileDescriptor fd, long offset, int whence) throws
            ExecutionFailedException {
        try {
            return Os.lseek(fd, offset, whence);
        } catch (ErrnoException e) {
            throw new ExecutionFailedException(e);
        }
    }
}
