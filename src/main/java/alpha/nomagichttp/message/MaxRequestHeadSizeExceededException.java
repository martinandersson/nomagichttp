package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpServer;

/**
 * Thrown by the server if the size of an inbound request head exceeds the
 * configured tolerance.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see HttpServer.Config#maxRequestHeadSize()
 */
public final class MaxRequestHeadSizeExceededException extends RuntimeException {
    // Empty
}