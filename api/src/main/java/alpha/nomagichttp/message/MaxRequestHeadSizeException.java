package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;

import java.io.Serial;

/**
 * Thrown by the server if the size of an inbound request head exceeds the
 * configured tolerance.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Config#maxRequestHeadSize()
 */
public final class MaxRequestHeadSizeException extends AbstractSizeException
{
    @Serial
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a {@code MaxRequestHeadSizeException}.
     * 
     * @param configuredMax the exceeded tolerance
     */
    public MaxRequestHeadSizeException(int configuredMax) {
        super(configuredMax);
    }
}