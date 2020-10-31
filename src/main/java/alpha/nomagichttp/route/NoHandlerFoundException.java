package alpha.nomagichttp.route;

import alpha.nomagichttp.message.MediaType;

/**
 * TODO: Docs
 */
// https://www.w3.org/Protocols/rfc2616/rfc2616-sec5.html#sec5.1.1
// TODO: Server should respond "501 (Not Implemented) if the method is unrecognized or not implemented by the origin server"
public class NoHandlerFoundException extends RuntimeException
{
    private final String method;
    private final Route route;
    private final MediaType contentType;
    private final MediaType[] accepts;
    
    NoHandlerFoundException(
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
        // Both of these can be null or empty
        this.contentType = contentType;
        this.accepts = accepts;
    }
    
    /**
     * Returns the request's HTTP method
     * 
     * @return the request's HTTP method (never {@code null} or empty)
     */
    public String method() {
        return method;
    }
    
    /**
     * Returns the request target (aka. "route") for which no handler was found.
     * 
     * @return the request target (never {@code null})
     */
    public Route route() {
        return route;
    }
    
    /**
     * Returns the "Content-Type" request header value
     * 
     * @return Content-Type (may be {@code null})
     */
    public MediaType contentType() {
        return contentType;
    }
    
    /**
     * Returns the "Accept" request header value(s)
     * 
     * @return Accept (may be {@code null} or empty)
     */
    public MediaType[] accepts() {
        return accepts;
    }
}