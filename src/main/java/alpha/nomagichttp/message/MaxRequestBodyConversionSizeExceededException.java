package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;

/**
 * Thrown if the size of an inbound request body exceeds the maximum buffer
 * limit.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Config#maxRequestBodyConversionSize()
 */
public final class MaxRequestBodyConversionSizeExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a {@code MaxRequestBodyConversionSizeExceededException}.
     */
    public MaxRequestBodyConversionSizeExceededException() {
        // Empty
    }
}