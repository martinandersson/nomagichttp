package alpha.nomagichttp.handler;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import static alpha.nomagichttp.message.Responses.internalServerError;
import static alpha.nomagichttp.message.Responses.upgradeRequired;
import static java.util.Objects.requireNonNull;

/**
 * A response has been rejected for writing.<p>
 * 
 * Is thrown by {@link ChannelWriter#write(Response)} if a response is rejected
 * for a {@link #reason()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class ResponseRejectedException
             extends RuntimeException implements HasResponse
{
    // TODO: Encapsulate reason in static factories, just like ExchangeDeath
    
    /**
     * Is a reason why a response was rejected.
     */
    public enum Reason {
        /**
         * The response status-code is 1XX, but the request failed to parse.<p>
         * 
         * The request processing chain was never invoked, because the request
         * never parsed. And so, this reason indicates that an exception handler
         * attempted to write an informational response, which is kind of weird.
         */
        CLIENT_PROTOCOL_UNKNOWN_BUT_NEEDED,
        
        /**
         * The response status-code is 1XX, and HTTP version used by the client
         * is {@literal <} 1.1.
         */
        CLIENT_PROTOCOL_DOES_NOT_SUPPORT;
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
    
    /**
     * {@inheritDoc}
     * 
     * <table>
     *   <caption style="display:none">Reason to response mappings</caption>
     *   <thead>
     *   <tr>
     *     <th scope="col">Reason</th>
     *     <th scope="col">Yields</th>
     *   </tr>
     *   </thead>
     *   <tbody>
     *   <tr>
     *     <th scope="row">{@link Reason#CLIENT_PROTOCOL_UNKNOWN_BUT_NEEDED
     *         CLIENT_PROTOCOL_UNKNOWN_BUT_NEEDED}</th>
     *     <td>{@link Responses#internalServerError()}</td>
     *   </tr>
     *   <tr>
     *     <th scope="row">{@link Reason#CLIENT_PROTOCOL_DOES_NOT_SUPPORT
     *         CLIENT_PROTOCOL_DOES_NOT_SUPPORT}</th>
     *     <td>{@link Responses#upgradeRequired(String)}</td>
     *   </tr>
     *   </tbody>
     * </table>
     * 
     * @return see Javadoc
     */
    @Override
    public Response getResponse() {
        return switch (reason()) {
            // TODO: Log internal server error after logging support
            case CLIENT_PROTOCOL_UNKNOWN_BUT_NEEDED
                     -> internalServerError();
            case CLIENT_PROTOCOL_DOES_NOT_SUPPORT
                     -> upgradeRequired("HTTP/1.1");
            default  -> throw new AssertionError();
        };
    }
}