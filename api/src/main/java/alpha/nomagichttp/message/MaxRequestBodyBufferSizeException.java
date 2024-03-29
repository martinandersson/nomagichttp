package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;

import java.io.Serial;

/**
 * Thrown if the size of an inbound request body exceeds the maximum buffer
 * limit.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Config#maxRequestBodyBufferSize()
 */
public final class MaxRequestBodyBufferSizeException extends AbstractSizeException
{
    @Serial
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a {@code MaxRequestBodyBufferSizeException}.
     * 
     * @param configuredMax the exceeded tolerance
     */
    public MaxRequestBodyBufferSizeException(int configuredMax) {
        super(configuredMax);
    }
}