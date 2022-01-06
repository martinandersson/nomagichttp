package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;

/**
 * Thrown by the server if the size of an inbound request head exceeds the
 * configured tolerance.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Config#maxRequestHeadSize()
 */
public class MaxRequestHeadSizeExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    // Empty
}