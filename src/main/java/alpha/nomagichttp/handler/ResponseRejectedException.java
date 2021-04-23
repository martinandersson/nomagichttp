package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.Response;

import static java.util.Objects.requireNonNull;

/**
 * A response has been rejected for writing.<p>
 * 
 * Is thrown by the server if a response is rejected for a {@link #reason()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see ClientChannel#write(Response)
 * @see ErrorHandler
 */
public class ResponseRejectedException extends RuntimeException
{
    /**
     * The reason why a response was rejected.
     */
    public enum Reason {
        // TODO: Also need to "close" exchange on channel close.
        /**
         * A final response has already been transmitted (in parts or in whole).
         */
        EXCHANGE_NOT_ACTIVE,
        
        /**
         * The response status-code is 1XX and HTTP version used is {@literal <} 1.1.
         */
        PROTOCOL_NOT_SUPPORTED;
    }
    
    private static final long serialVersionUID = 1L;
    
    private final Response rejected;
    private final Reason reason;
    
    /**
     * Constructs a {@code ResponseRejectedException}.
     * 
     * @param rejected response
     * @param reason why
     * @param message passed through as-is to {@link Throwable#Throwable(String)}
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    public ResponseRejectedException(Response rejected, Reason reason, String message) {
        super(message);
        this.rejected = requireNonNull(rejected);
        this.reason   = requireNonNull(reason);
    }
    
    /**
     * Returns the rejected response.
     * 
     * @return the rejected response (never {@code null})
     */
    public Response rejected() {
        return rejected;
    }
    
    /**
     * Returns the reason why the response was rejected.
     * 
     * @return the reason why the response was rejected (never {@code null})
     */
    public Reason reason() {
        return reason;
    }
}