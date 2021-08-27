package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;

/**
 * Thrown by the server on response timeout.<p>
 * 
 * The default error handler will upon receiving this error shut down the client
 * channel's read stream and respond {@link Responses#serviceUnavailable()}. The
 * underlying response pipeline (a collaborator/component of the client channel
 * implementation) who throws this exception will give up waiting on channel
 * closure after another 5 seconds have passed and proceed to close the channel
 * without any further ado.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see Config#timeoutIdleConnection()
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