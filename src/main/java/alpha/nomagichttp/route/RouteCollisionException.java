package alpha.nomagichttp.route;

/**
 * Thrown by {@link RouteRegistry} when an attempt is made to register a route
 * which is equivalent to an already registered route.<p>
 * 
 * For example, the route {@code "/"} is equivalent to {@code "/{param}"}
 * because parameters are optional and these two have the same identity.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route
 * @see AmbiguousRouteCollisionException
 */
public class RouteCollisionException extends RuntimeException {
    public RouteCollisionException(String message) {
        super(message);
    }
}