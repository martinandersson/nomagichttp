package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;

import java.util.Set;

import static java.util.Collections.unmodifiableSet;

/**
 * The request handler resolution process ended ambiguously.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see ErrorHandler#DEFAULT
 */
public final class AmbiguousHandlerException extends NoHandlerResolvedException
{
    private static final long serialVersionUID = 1L;
    
    private final Set<RequestHandler> ambiguous;
    
    static AmbiguousHandlerException createAmbiguousEx(
            Set<RequestHandler> ambiguous,
            String method,
            Route route,
            MediaType contentType,
            MediaType[] accepts)
    {
        return new AmbiguousHandlerException(
                "Ambiguous: " + ambiguous,
                ambiguous,
                method,
                route,
                contentType,
                accepts);
    }
    
    private AmbiguousHandlerException(
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
    public Set<RequestHandler> getCandidates() {
        return ambiguous;
    }
}
