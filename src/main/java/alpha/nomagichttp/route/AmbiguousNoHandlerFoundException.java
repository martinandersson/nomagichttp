package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;

import java.util.Set;

import static java.util.Collections.unmodifiableSet;

/**
 * Thrown by the HTTP server if the {@link RequestHandler} resolution process
 * ends ambiguously. The response produced by {@link ErrorHandler#DEFAULT}
 * is "501 Not Implemented".
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class AmbiguousNoHandlerFoundException extends NoHandlerFoundException {
    private final Set<RequestHandler> ambiguous;
    
    static AmbiguousNoHandlerFoundException createAmbiguousEx(
            Set<RequestHandler> ambiguous,
            String method,
            Route route,
            MediaType contentType,
            MediaType[] accepts)
    {
        return new AmbiguousNoHandlerFoundException(
                "Ambiguous: " + ambiguous,
                ambiguous,
                method,
                route,
                contentType,
                accepts);
    }
    
    private AmbiguousNoHandlerFoundException(
            String message,
            Set<RequestHandler> ambiguous,
            String method,
            Route route,
            MediaType contentType,
            MediaType[] accepts)
    {
        super(message, method, route, contentType, accepts);
        
        if (ambiguous.isEmpty()) {
            throw new IllegalArgumentException("No ambiguous candidates.");
        }
        
        this.ambiguous = unmodifiableSet(ambiguous);
    }
    
    /**
     * Returns all ambiguous candidates that qualified.
     * 
     * @return all ambiguous candidates that qualified (never {@code null} or empty)
     */
    public Set<RequestHandler> candidates() {
        return ambiguous;
    }
}
