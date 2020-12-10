package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;

import java.util.Set;

import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

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
        this.ambiguous = unmodifiableSet(requireNonNull(ambiguous));
    }
    
    public Set<RequestHandler> ambiguous() {
        return ambiguous;
    }
}
