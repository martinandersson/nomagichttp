package alpha.nomagichttp;

import alpha.nomagichttp.handler.ErrorHandler;

/**
 * Thrown when a connection is deemed to be idle.<p>
 * 
 * This exception is thrown by the request thread invoking a read or write
 * operation on the client channel, after a background thread triggered by the
 * timeout shuts down respective stream.<p>
 * 
 * If this exception is passed to an error handler, it can only be because the
 * input stream shut down. Also, the output stream remains open and has not been
 * used for writing bytes. Consequently, the
 * {@linkplain ErrorHandler#BASE base error handler} translates this response to
 * 408 (Request Timeout).<p>
 * 
 * If the write operation times out, then the write stream will shut down and
 * the connection will come to an abrupt end. No error handler called.<p>
 * 
 * There will never in a million years make much sense to even try writing
 * a fallback response in such a case. Why would the second attempt succeed
 * better than the failed operation?<p>
 * 
 * To be uber clear, the base handler never responds 503 (Service Unavailable).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Config#timeoutIdleConnection()
 */
public final class IdleConnectionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs this object.
     */
    public IdleConnectionException() {
        // super
    }
}
