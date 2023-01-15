package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;

import java.util.Collection;
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
    
    private final transient Set<RequestHandler> ambiguous;
    
    static AmbiguousHandlerException createAmbiguousEx(
            Route route,
            String method,
            MediaType contentType,
            Collection<MediaType> accepts,
            Set<RequestHandler> ambiguous)
    {
        return new AmbiguousHandlerException(
                "Ambiguous: " + ambiguous,
                route,
                method,
                contentType,
                accepts,
                ambiguous);
    }
    
    private AmbiguousHandlerException(
            String message,
            Route route,
            String method,
            MediaType contentType,
            Collection<MediaType> accepts,
            Set<RequestHandler> ambiguous)
    {
        super(message, route, method, contentType, accepts);
        if (ambiguous.isEmpty()) {
            throw new IllegalArgumentException("No ambiguous candidates.");
        }
        this.ambiguous = unmodifiableSet(ambiguous);
    }
    
    /**
     * Returns all ambiguous candidates that qualified.
     * 
     * @return all ambiguous candidates that qualified
     *         (never {@code null} or empty)
     */
    public Set<RequestHandler> getCandidates() {
        return ambiguous;
    }
}