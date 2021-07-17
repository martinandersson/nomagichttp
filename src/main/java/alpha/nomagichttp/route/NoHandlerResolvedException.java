package alpha.nomagichttp.route;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;

/**
 * A common supertype to indicate that the {@link RequestHandler} resolution
 * process could not find one specific qualified handler. The specialized
 * subclass indicates <i>why</i> the handler could not be resolved.<p>
 * 
 * Is thrown by {@link Route#lookup(String, MediaType, MediaType[])}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public abstract class NoHandlerResolvedException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    private static final MediaType[] EMPTY = new MediaType[0];
    
    private final String method;
    private final Route route;
    private final MediaType contentType;
    private final MediaType[] accepts;
    
    /**
     * Constructs this object.
     * 
     * @param message passed as-is to {@link Throwable#Throwable(String)}
     * @param method of request
     * @param route matched
     * @param contentType of request
     * @param accepts of request
     */
    public NoHandlerResolvedException(
            String message,
            String method,
            Route route,
            MediaType contentType,
            MediaType[] accepts)
    {
        super(message);
        // Validated by RequestHeadProcessor.parseMethod()
        this.method = method;
        // DefaultRoute passes <this> as c-tor arg
        this.route = route;
        this.contentType = contentType;
        this.accepts = accepts == null ? EMPTY : accepts;
    }
    
    /**
     * Returns the request's HTTP method.
     * 
     * @return the request's HTTP method (never {@code null} or empty)
     */
    public String getMethod() {
        return method;
    }
    
    /**
     * Returns the request target (aka. "route").
     * 
     * @return the request target (never {@code null})
     */
    public Route getRoute() {
        return route;
    }
    
    /**
     * Returns the "Content-Type" request header value.
     * 
     * @return Content-Type (may be {@code null})
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     */
    public MediaType getContentType() {
        return contentType;
    }
    
    /**
     * Returns the "Accept" request header value(s).
     * 
     * @return Accept (may be empty, never {@code null})
     * @see HttpConstants.HeaderKey#ACCEPT
     */
    public MediaType[] getAccepts() {
        return accepts;
    }
}