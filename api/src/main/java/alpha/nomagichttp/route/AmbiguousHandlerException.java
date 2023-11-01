package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.ExceptionHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import java.io.Serial;
import java.util.Collection;
import java.util.Set;

import static alpha.nomagichttp.message.Responses.internalServerError;
import static java.util.Collections.unmodifiableSet;

/**
 * The request handler resolution process ended ambiguously.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see ExceptionHandler#BASE
 */
public final class AmbiguousHandlerException extends NoHandlerResolvedException
{
    @Serial
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
    
    /**
     * {@return {@link Responses#internalServerError()}}
     */
    @Override
    public Response getResponse() {
        return internalServerError();
    }
}
