package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;

/**
 * Thrown by the server if the size of request trailers exceeds the configured
 * tolerance.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Config#maxRequestTrailersSize()
 */
public class MaxRequestTrailersSizeExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    // Empty
}