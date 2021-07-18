package alpha.nomagichttp.route;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.message.MediaType;

import static alpha.nomagichttp.HttpConstants.HeaderKey.ACCEPT;
import static java.text.MessageFormat.format;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;

/**
 * Thrown by {@link Route#lookup(String, MediaType, MediaType[])} if the
 * requested media type is not produced by any one of the registered handlers
 * (failed content negotiation).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see ErrorHandler#DEFAULT
 */
public class MediaTypeNotAcceptedException extends NoHandlerResolvedException {
    private static final long serialVersionUID = 1L;
    
    static MediaTypeNotAcceptedException unmatchedAccept(
            String method, Route route, MediaType contentType, MediaType[] accepts)
    {
        String msg = format("No handler found matching \"{0}\" header in request.",
                ACCEPT + ": " + stream(accepts).map(Object::toString).collect(joining(", ")));
        return new MediaTypeNotAcceptedException(msg, method, route, contentType, accepts);
    }
    
    /**
     * Constructs this object.
     * 
     * @param message     passed as-is to {@link Throwable#Throwable(String)}
     * @param method      of request
     * @param route       matched
     * @param contentType of request
     * @param accepts     of request
     * 
     * @throws NullPointerException if {@code accepts} is {@code null}
     * @throws IllegalArgumentException if {@code accepts} is empty
     */
    public MediaTypeNotAcceptedException(String message, String method, Route route, MediaType contentType, MediaType[] accepts) {
        super(message, method, route, contentType, requireNotEmpty(accepts));
    }
    
    private static MediaType[] requireNotEmpty(MediaType[] accepts) {
        if (accepts.length == 0) {
            throw new IllegalArgumentException("Empty \"" + ACCEPT+ "\" header values.");
        }
        return accepts;
    }
    
    /**
     * {@inheritDoc}
     * 
     * @return Accept (never {@code null} or empty)
     * @see HttpConstants.HeaderKey#ACCEPT
     */
    @Override
    public MediaType[] getAccepts() {
        return super.getAccepts();
    }
}