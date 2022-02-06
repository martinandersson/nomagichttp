package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;

/**
 * Response timed out.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see Config#timeoutResponse()
 */
public final class ResponseTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a {@code ResponseTimeoutException}.
     * 
     * @param message passed through as-is to {@link Throwable#Throwable(String)}
     */
    public ResponseTimeoutException(String message) {
        super(message);
    }
}