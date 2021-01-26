package alpha.nomagichttp.route;

import static java.util.stream.Collectors.joining;
import static java.util.stream.StreamSupport.stream;

/**
 * TODO: Docs
 * 
 * @see RouteRegistry#lookup(Iterable) 
 */
public class NoRouteFoundException extends RuntimeException
{
    private final String path;
    
    public NoRouteFoundException(Iterable<String> pathSegments) {
        this.path = "/" + stream(pathSegments.spliterator(), false).collect(joining("/"));
    }
    
    /**
     * Returns the request path for which no {@link Route} was found.
     * 
     * @return the request path for which no {@link Route} was found
     *         (never {@code null} or the empty string)
     */
    public String path() {
        return path;
    }
}