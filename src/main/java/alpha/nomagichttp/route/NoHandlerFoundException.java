package alpha.nomagichttp.route;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;

import static alpha.nomagichttp.HttpConstants.HeaderKey.ACCEPT;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_TYPE;
import static java.text.MessageFormat.format;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;

/**
 * Thrown by the HTTP server if the {@link RequestHandler} resolution process
 * does not find a qualified handler. The response produced by {@link
 * ErrorHandler#DEFAULT} is "501 Not Implemented".
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class NoHandlerFoundException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    static NoHandlerFoundException unmatchedContentType(
            String method, Route route, MediaType contentType, MediaType[] accepts)
    {
        String msg = format("No handler found matching \"{0}\" and/or \"{1}\" header in request.",
                // Arg 0
                CONTENT_TYPE + ": " + (contentType == null ? "[N/A]" : contentType),
                // Arg 1
                ACCEPT + ": " + (accepts == null || accepts.length == 0 ? "[N/A]" :
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
    
    /**
     * Constructs a {@code NoHandlerFoundException}.
     * 
     * @param message passed as-is to {@link Throwable#Throwable(String)}
     * @param method of request
     * @param route matched
     * @param contentType of request
     * @param accepts of request
     */
    public NoHandlerFoundException(
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
        this.accepts = accepts == null ? new MediaType[0] : accepts;
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
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     */
    public MediaType contentType() {
        return contentType;
    }
    
    /**
     * Returns the "Accept" request header value(s)
     * 
     * @return Accept (may be empty, never {@code null})
     * @see HttpConstants.HeaderKey#ACCEPT
     */
    public MediaType[] accepts() {
        return accepts;
    }
}