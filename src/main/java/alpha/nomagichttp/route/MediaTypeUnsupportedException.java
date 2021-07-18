package alpha.nomagichttp.route;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.message.MediaType;

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_TYPE;
import static java.text.MessageFormat.format;
import static java.util.Objects.requireNonNull;

/**
 * Thrown by {@link Route#lookup(String, MediaType, MediaType[])} if no
 * registered handler consumes the message payload.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see ErrorHandler#DEFAULT
 */
public class MediaTypeUnsupportedException extends NoHandlerResolvedException {
    private static final long serialVersionUID = 1L;
    
    static MediaTypeUnsupportedException unmatchedContentType(
            String method, Route route, MediaType contentType, MediaType[] accepts)
    {
        String msg = format("No handler found matching \"{0}\" header in request.",
                CONTENT_TYPE + ": " + contentType);
        return new MediaTypeUnsupportedException(msg, method, route, contentType, accepts);
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
     * @throws NullPointerException if {@code contentType} is {@code null}
     */
    public MediaTypeUnsupportedException(String message, String method, Route route, MediaType contentType, MediaType[] accepts) {
        super(message, method, route, requireNonNull(contentType), accepts);
    }
    
    /**
     * {@inheritDoc}
     * 
     * @return Content-Type (never {@code null})
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     */
    @Override
    public MediaType getContentType() {
        return super.getContentType();
    }
}