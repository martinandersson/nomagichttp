package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.Response;

import static java.util.Objects.requireNonNull;

/**
 * A response has been rejected for writing.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see ClientChannel#write(Response)
 * @see ErrorHandler
 */
public class ResponseRejectedException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    private final Response rejected;
    
    /**
     * Constructs a {@code ResponseRejectedException}.
     * 
     * @param rejected response
     * @param message passed through as-is to {@link Throwable#Throwable(String)}
     * 
     * @throws NullPointerException if {@code rejected} is {@code null}
     */
    public ResponseRejectedException(Response rejected, String message) {
        super(message);
        this.rejected = requireNonNull(rejected);
    }
    
    /**
     * Returns the rejected response.
     * 
     * @return the rejected response (never {@code null})
     */
    public Response rejected() {
        return rejected;
    }
}