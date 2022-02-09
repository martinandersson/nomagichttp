package alpha.nomagichttp.route;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;

import java.util.Collection;

import static java.util.Objects.requireNonNull;

/**
 * A common supertype to indicate that the {@link RequestHandler} resolution
 * process could not find one specific qualified handler. The specialized
 * subclass indicates <i>why</i> the handler could not be resolved.<p>
 * 
 * Is thrown by {@link Route#lookup(String, MediaType, Collection)}. In fact, the
 * getters in this class will return the route instance called and the arguments
 * used.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public abstract class NoHandlerResolvedException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    private final String method;
    private final Route route;
    private final MediaType contentType;
    private final Collection<MediaType> accepts;
    
    /**
     * Constructs this object.
     * 
     * @param message     passed as-is to {@link Throwable#Throwable(String)}
     * @param route       matched target for the lookup operation
     * @param method      of request (first argument to lookup)
     * @param contentType of request (second argument to lookup)
     * @param accepts     of request (third argument to lookup)
     */
    public NoHandlerResolvedException(
            String message,
            Route route,
            String method,
            MediaType contentType,
            Collection<MediaType> accepts)
    {
        super(message);
        // DefaultRoute passes <this> as c-tor arg
        this.route = requireNonNull(route);
        this.method = method;
        this.contentType = contentType;
        this.accepts = accepts;
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
     * Returns the request's HTTP method.
     * 
     * @return the request's HTTP method (never {@code null})
     */
    public String getMethod() {
        return method;
    }
    
    /**
     * Returns the "Content-Type" request header value.
     * 
     * @return Content-Type (may be {@code null})
     * @see HttpConstants.HeaderName#CONTENT_TYPE
     */
    public MediaType getContentType() {
        return contentType;
    }
    
    /**
     * Returns the "Accept" request header value(s).<p>
     * 
     * The returned collection may be unmodifiable. Even if it isn't, it
     * shouldn't be modified. Doing so may lead to undefined application
     * behavior.
     * 
     * @return Accept (may be {@code null})
     * @see HttpConstants.HeaderName#ACCEPT
     */
    public Collection<MediaType> getAccepts() {
        return accepts;
    }
}