package alpha.nomagichttp.route;

/**
 * Thrown by {@link RouteRegistry} when an attempt is made to register a route
 * which could have hidden an already registered route.<p>
 * 
 * For example, the route {@code "/where"} is effectively equivalent to {@code
 * "/{param}"} because a request target "/where" would have matched both.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 *
 * @see Route
 * @see RouteCollisionException
 */
public final class AmbiguousRouteCollisionException extends RouteCollisionException
{
    public AmbiguousRouteCollisionException(String message) {
        super(message);
    }
}