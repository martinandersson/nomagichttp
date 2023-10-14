package alpha.nomagichttp.route;

import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import java.io.Serial;
import java.util.Collection;

import static alpha.nomagichttp.HttpConstants.HeaderName.ACCEPT;
import static alpha.nomagichttp.message.Responses.notAcceptable;
import static java.text.MessageFormat.format;
import static java.util.stream.Collectors.joining;

/**
 * Thrown by {@link Route#lookup(String, MediaType, Collection)} if the
 * requested media type is not produced by any one of the registered handlers
 * (failed content negotiation).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class MediaTypeNotAcceptedException
             extends NoHandlerResolvedException
{
    @Serial
    private static final long serialVersionUID = 1L;
    
    static MediaTypeNotAcceptedException unmatchedAccept(
            Route route, String method, MediaType contentType, Collection<MediaType> accepts)
    {
        String msg = format("No handler found matching \"{0}\" header in request.",
                ACCEPT + ": " + accepts.stream().map(Object::toString).collect(joining(", ")));
        return new MediaTypeNotAcceptedException(msg, route, method, contentType, accepts);
    }
    
    /**
     * Constructs this object.
     * 
     * @param message     passed as-is to {@link Throwable#Throwable(String)}
     * @param route       matched target for the lookup operation
     * @param method      of request (first argument to lookup)
     * @param contentType of request (second argument to lookup)
     * @param accepts     of request (third argument to lookup)
     * 
     * @throws NullPointerException if {@code accepts} is {@code null}
     * @throws IllegalArgumentException if {@code accepts} is empty
     */
    public MediaTypeNotAcceptedException(
            String message, Route route, String method,
            MediaType contentType, Collection<MediaType> accepts)
    {
        super(message, route, method, contentType, requireNotEmpty(accepts));
    }
    
    private static Collection<MediaType> requireNotEmpty(Collection<MediaType> accepts) {
        if (accepts.isEmpty()) {
            throw new IllegalArgumentException("Empty \"" + ACCEPT+ "\" header values.");
        }
        return accepts;
    }
    
    /**
     * Returns {@link Responses#notAcceptable()}.
     * 
     * @return see Javadoc
     */
    @Override
    public Response getResponse() {
        return notAcceptable();
    }
}