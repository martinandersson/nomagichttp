package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;

/**
 * Thrown if the size of an inbound request body exceeds the maximum buffer
 * limit.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Config#maxRequestBodyBufferSize()
 */
public final class MaxRequestBodyBufferSizeException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a {@code MaxRequestBodyBufferSizeException}.
     */
    public MaxRequestBodyBufferSizeException() {
        // Empty
    }
}