package alpha.nomagichttp.route;

import alpha.nomagichttp.message.MediaType;

import static java.text.MessageFormat.format;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;

/**
 * TODO: Docs
 */
// https://www.w3.org/Protocols/rfc2616/rfc2616-sec5.html#sec5.1.1
// TODO: Server should respond "501 (Not Implemented) if the method is unrecognized or not implemented by the origin server"
public class NoHandlerFoundException extends RuntimeException
{
    static NoHandlerFoundException unmatchedContentType(
            String method, Route route, MediaType contentType, MediaType[] accepts)
    {
        String msg = format("No handler found matching \"{0}\" and/or \"{1}\" header in request.",
                // Arg 0
                "Content-Type: " + (contentType == null ? "[N/A]" : contentType),
                // Arg 1
                "Accept: " + (accepts == null || accepts.length == 0 ? "[N/A]" :
                        stream(accepts).map(Object::toString).collect(joining(", "))));
        
        return new NoHandlerFoundException(msg, method, route, contentType, accepts);
    }
    
    static NoHandlerFoundException unmatchedMethod(
            String method, Route route, MediaType contentType, MediaType[] accepts)
    {
        return new NoHandlerFoundException(
                "No handler found for method token \"" + method + "\".",
                method, route, contentType, accepts);
    }
    
    private final String method;
    private final Route route;
    private final MediaType contentType;
    private final MediaType[] accepts;
    
    private NoHandlerFoundException(
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