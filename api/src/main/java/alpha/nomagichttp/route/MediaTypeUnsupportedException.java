package alpha.nomagichttp.route;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import java.util.Collection;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_TYPE;
import static alpha.nomagichttp.message.Responses.unsupportedMediaType;
import static java.text.MessageFormat.format;
import static java.util.Objects.requireNonNull;

/**
 * Thrown by {@link Route#lookup(String, MediaType, Collection)} if no
 * registered handler consumes the message payload.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class MediaTypeUnsupportedException
             extends NoHandlerResolvedException
{
    private static final long serialVersionUID = 1L;
    
    static MediaTypeUnsupportedException unmatchedContentType(
            Route route, String method, MediaType contentType, Collection<MediaType> accepts)
    {
        String msg = format("No handler found matching \"{0}\" header in request.",
                CONTENT_TYPE + ": " + contentType);
        return new MediaTypeUnsupportedException(msg, route, method, contentType, accepts);
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
     * @throws NullPointerException if {@code contentType} is {@code null}
     */
    public MediaTypeUnsupportedException(
            String message, Route route, String method,
            MediaType contentType, Collection<MediaType> accepts)
    {
        super(message, route, method, requireNonNull(contentType), accepts);
    }
    
    /**
     * {@inheritDoc}
     * 
     * @return Content-Type (never {@code null})
     * @see HttpConstants.HeaderName#CONTENT_TYPE
     */
    @Override
    public MediaType getContentType() {
        return super.getContentType();
    }
    
    /**
     * Returns {@link Responses#unsupportedMediaType()}.
     * 
     * @return see Javadoc
     */
    @Override
    public Response getResponse() {
        return unsupportedMediaType();
    }
}