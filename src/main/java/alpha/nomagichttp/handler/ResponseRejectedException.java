package alpha.nomagichttp.handler;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.message.Response;

import static java.util.Objects.requireNonNull;

/**
 * A response has been rejected for writing.<p>
 * 
 * Is thrown by {@link ChannelWriter#write(Response)} if a response is rejected
 * for a {@link #reason()}. The {@link ErrorHandler#BASE base error
 * handler} will translate this exception to a {@value
 * HttpConstants.StatusCode#FOUR_HUNDRED_TWENTY_SIX} ({@value
 * HttpConstants.ReasonPhrase#UPGRADE_REQUIRED}) response.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class ResponseRejectedException extends RuntimeException
{
    // TODO: Encapsulate reason in static factories, just like ExchangeDeath
    
    /**
     * The reason why a response was rejected.
     */
    public enum Reason {
        /**
         * The response status-code is 1XX and HTTP version used is {@literal <} 1.1.
         */
        PROTOCOL_NOT_SUPPORTED;
        
        // TODO: Any other reason added here will require JavaDoc update
    }
    
    private static final long serialVersionUID = 1L;
    
    private final transient Response rejected;
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