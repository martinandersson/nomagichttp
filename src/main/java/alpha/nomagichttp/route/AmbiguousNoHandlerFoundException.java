package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.Handler;

import java.util.Set;

import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

// TODO: Document
public final class AmbiguousNoHandlerFoundException extends RuntimeException {
    private final Set<Handler> ambiguous;
    
    AmbiguousNoHandlerFoundException(Set<Handler> ambiguous, String message) {
        super(message);
        this.ambiguous = unmodifiableSet(requireNonNull(ambiguous));
    }
    
    public Set<Handler> ambiguous() {
        return ambiguous;
    }
}
