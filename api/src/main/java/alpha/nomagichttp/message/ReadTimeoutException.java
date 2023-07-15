package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;

import java.nio.channels.InterruptedByTimeoutException;

/**
 * A channel read operation did not complete in time.
 * 
 * @see Config#timeoutRead()
 */
public class ReadTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    /**
     * Initializes this object.
     * 
     * @param cause passed as-is to {@link Throwable#Throwable(Throwable)}
     */
    public ReadTimeoutException(InterruptedByTimeoutException cause) {
        super(cause);
    }
}