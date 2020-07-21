package alpha.nomagichttp.message;

import alpha.nomagichttp.ServerConfig;

/**
 * Thrown by the server if the size of an inbound request head exceeds the
 * configured tolerance.<p>
 * 
 * This exception is thrown before the route has been matched and can therefor
 * not be observed by a route-level exception handler. Only by a server-level
 * exception handler.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see ServerConfig#maxRequestHeadSize()
 */
public final class MaxRequestHeadSizeExceededException extends RuntimeException {
    // Empty
}