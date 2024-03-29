package alpha.nomagichttp.util;

import java.io.Serial;

/**
 * Thrown by {@link JvmPathLock} if a read or write lock is not acquired within
 * a specified time frame.
 */
public final class FileLockTimeoutException extends Exception
{
    @Serial
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
