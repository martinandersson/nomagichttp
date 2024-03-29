package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;

import java.io.Serial;

/**
 * Thrown by the server if the size of request trailers exceeds the configured
 * tolerance.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Config#maxRequestTrailersSize()
 */
public final class MaxRequestTrailersSizeException extends AbstractSizeException
{
    @Serial
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a {@code MaxRequestTrailersSizeException}.
     * 
     * @param configuredMax the exceeded tolerance
     */
    public MaxRequestTrailersSizeException(int configuredMax) {
        super(configuredMax);
    }
}
