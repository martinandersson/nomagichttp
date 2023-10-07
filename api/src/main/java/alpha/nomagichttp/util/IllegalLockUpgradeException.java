package alpha.nomagichttp.util;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A thread holding a read-lock attempted to acquire a write-lock.<p>
 * 
 * The fix for this exception is quite simple; release the read-lock before
 * attempting to acquire a write-lock.
 * 
 * @see JvmPathLock#writeLock(Path, long, TimeUnit) 
 */
public final class IllegalLockUpgradeException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs this object.
     */
    IllegalLockUpgradeException() {
        // Empty
    }
}