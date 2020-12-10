package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;

import java.util.Set;

import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

public final class AmbiguousNoHandlerFoundException extends RuntimeException {
/**
 * Thrown by the HTTP server if the {@link RequestHandler} resolution process
 * ends ambiguously. The response produced by {@link ErrorHandler#DEFAULT}
 * is "501 Not Implemented".
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
    private final Set<RequestHandler> ambiguous;
    
    AmbiguousNoHandlerFoundException(Set<RequestHandler> ambiguous, String message) {
        super(message);
        this.ambiguous = unmodifiableSet(requireNonNull(ambiguous));
    }
    
    public Set<RequestHandler> ambiguous() {
        return ambiguous;
    }
}
