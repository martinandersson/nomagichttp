package alpha.nomagichttp.util;

import alpha.nomagichttp.handler.ErrorHandler;

/**
 * Thrown by {@link JvmPathLock} if a read or write lock is not acquired within
 * a specified time frame.<p>
 * 
 * Is translated by the server's
 * {@linkplain ErrorHandler#BASE base error handler} to
 * 500 (Internal Server Error).
 */
public final class FileLockTimeoutException extends Exception {
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a {@code FileLockTimeoutException}.
     * 
     * @param message passed as-is to {@link Throwable#Throwable(String)}
     */
    FileLockTimeoutException(String message) {
        super(message);
    }
}
