package alpha.nomagichttp;

import alpha.nomagichttp.handler.HasResponse;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import static alpha.nomagichttp.message.Responses.requestTimeout;

/**
 * Thrown when a connection is deemed to be idle.<p>
 * 
 * This exception is thrown by the request thread invoking a read or write
 * operation on the client channel, after a background thread triggered by the
 * timeout shuts down the respective stream.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Config#timeoutIdleConnection()
 */
public final class IdleConnectionException
             extends RuntimeException implements HasResponse {
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs this object.
     */
    public IdleConnectionException() {
        // super
    }
    
    /**
     * {@inheritDoc}<p>
     * 
     * If this exception is passed to an exception handler, it can only be
     * because the input stream shut down, and the unused output stream remains
     * open.<p>
     * 
     * If the write operation times out, then the output stream will shut down
     * and the connection will come to an abrupt end. Meaning that for this
     * case, exception handlers are not called.<p>
     * 
     * Consequently, this method returns {@link Responses#requestTimeout()}.<p>
     * 
     * There will never in a million years make much sense to even try writing
     * a fallback response if the write operation times out. Why would the
     * second attempt succeed better than the failed one?<p>
     * 
     * To be uber clear, this method never returns 503 (Service Unavailable).
     * 
     * @return see Javadoc
     */
    @Override
    public Response getResponse() {
        return requestTimeout();
    }
}
