package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.ErrorHandler;

import static java.util.stream.Collectors.joining;
import static java.util.stream.StreamSupport.stream;

/**
 * Thrown by a route registry that failed to lookup a route because the route
 * was not found. {@link ErrorHandler#BASE} maps this exception to a "404 Not
 * Found" response.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class NoRouteFoundException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    private final transient Iterable<String> segments;
    
    /**
     * Constructs a {@code NoRouteFoundException}.
     * 
     * @param pathSegments of request target (normalized- and percent-decoded)
     */
    public NoRouteFoundException(Iterable<String> pathSegments) {
        super(path(pathSegments));
        this.segments = pathSegments;
    }
    
    /**
     * Returns normalized- and percent-decoded path segments from the request
     * target.
     * 
     * @return
     *     normalized- and percent-decoded path segments from the request target
     * 
     * @see Route
     */
    public Iterable<String> getSegments() {
        return segments;
    }
    
    /**
     * Returns the request path for which no {@link Route} was found.
     * 
     * @return the request path for which no {@link Route} was found
     *         (never {@code null} or the empty string)
     */
    public String getPath() {
        return path(getSegments());
    }
    
    private static String path(Iterable<String> pathSegments) {
        return "/" + stream(pathSegments.spliterator(), false).collect(joining("/"));
    } 
}