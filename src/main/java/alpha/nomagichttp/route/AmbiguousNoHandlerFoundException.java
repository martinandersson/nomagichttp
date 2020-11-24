package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.RequestHandler;

import java.util.Set;

import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

// TODO: Document
public final class AmbiguousNoHandlerFoundException extends RuntimeException {
    private final Set<RequestHandler> ambiguous;
    
    AmbiguousNoHandlerFoundException(Set<RequestHandler> ambiguous, String message) {
        super(message);
        this.ambiguous = unmodifiableSet(requireNonNull(ambiguous));
    }
    
    public Set<RequestHandler> ambiguous() {
        return ambiguous;
    }
}
