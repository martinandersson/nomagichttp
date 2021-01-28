package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.ErrorHandler;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.StreamSupport.stream;

/**
 * Thrown by a route registry that failed to lookup a route because the route
 * was not added. {@link ErrorHandler#DEFAULT} maps this exception to a "404 Not
 * Found" response.
 * 
 * @see RouteRegistry#lookup(Iterable) 
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class NoRouteFoundException extends RuntimeException
{
    private final Iterable<String> segments;
    
    public NoRouteFoundException(Iterable<String> pathSegments) {
        this.segments = requireNonNull(pathSegments);
    }
    
    /**
     * Returns normalized- and percent-decoded path segments as provided in the
     * request target.
     * 
     * @return normalized- and percent-decoded path segments as provided in the
     *         request target
     * 
     * @see Route
     */
    public Iterable<String> segments() {
        return segments;
    }
    
    /**
     * Returns the request path for which no {@link Route} was found.
     * 
     * @return the request path for which no {@link Route} was found
     *         (never {@code null} or the empty string)
     */
    public String path() {
        return "/" + stream(segments().spliterator(), false).collect(joining("/"));
    }
}