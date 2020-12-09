package alpha.nomagichttp.route;

/**
 * Thrown by {@link RouteRegistry} when an attempt is made to register a route
 * which could shadow an already registered route.<p>
 * 
 * For example, the route {@code "/segment"} is effectively equivalent to {@code
 * "/{param}"} because a request target "/segment" would have matched both.
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