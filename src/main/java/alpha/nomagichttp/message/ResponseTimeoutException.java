package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;

/**
 * The client channel timed out waiting on a response or a response body
 * publisher delayed emitting bytebuffers.
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