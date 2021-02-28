package alpha.nomagichttp.route;

/**
 * Thrown by {@link RouteRegistry} when an attempt is made to register a route
 * which is equivalent to an already registered route.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route
 */
public class RouteCollisionException extends RuntimeException {
    /**
     * Constructs a {@code RouteCollisionException}.
     * 
     * @param message passed as-is to {@link Throwable#Throwable(String)}
     */
    public RouteCollisionException(String message) {
        super(message);
    }
}