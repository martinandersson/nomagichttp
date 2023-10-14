package alpha.nomagichttp.route;

import java.io.Serial;

/**
 * Thrown by {@link RouteRegistry} when an attempt is made to register a route
 * which is equivalent to an already registered route.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route
 */
public class RouteCollisionException extends RuntimeException
{
    @Serial
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a {@code RouteCollisionException}.
     * 
     * @param message passed as-is to {@link Throwable#Throwable(String)}
     */
    public RouteCollisionException(String message) {
        super(message);
    }
}