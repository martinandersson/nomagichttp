package alpha.nomagichttp.route;

/**
 * Provides thread-safe operations over a group of routes.<p>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route
 */
public interface RouteRegistry {
    /**
     * Add a route to this registry.
     * 
     * @param route to add
     * 
     * @throws NullPointerException
     *           if {@code route} is {@code null}
     * 
     * @throws RouteCollisionException
     *           if an equivalent route has already been added
     * 
     * @see Route
     */
    default void add(Route route) throws RouteCollisionException {
        add(route, route.identity());
    }
    
    // TODO: Document
    void add(Route route, String identity) throws RouteCollisionException;
    
    /**
     * Remove a route from this registry.
     * 
     * @param route to remove
     * 
     * @return {@code true} if successful (route was added before), otherwise
     *         {@code false} (the route is unknown)
     * 
     * @throws NullPointerException if {@code route} is {@code null}
     */
    default boolean remove(Route route) {
        return remove(route.identity()) != null;
    }
    
    /**
     * Remove a route from this registry using a specified route identity.
     * 
     * @param routeIdentity route identity
     * 
     * @return the route if successful (route was added before), otherwise
     *         {@code null} (the route is unknown)
     * 
     * @throws NullPointerException if {@code routeIdentity} is {@code null}
     * 
     * @see Route
     */
    Route remove(String routeIdentity);
    
    /**
     * Lookup a route given a specified request-target.<p>
     * 
     * Similar to {@link Route#matches(String)} except all routes of this
     * registry will be searched and if no match is found, a
     * {@link NoRouteFoundException} is thrown (instead of returning {@code
     * null}).
     * 
     * @implNote
     * As per docs of {@link Route#matches(String)}, the query part of the
     * inbound request-target will be removed by the registry implementation.
     * There's no need for the caller to do so.
     * 
     * @param requestTarget extracted from the request-line of an inbound request
     * 
     * @return a match (not {@code null})
     * 
     * @throws NullPointerException   if {@code requestTarget} is {@code null}
     * @throws NoRouteFoundException  if no route was found
     */
    Route.Match lookup(String requestTarget) throws NoRouteFoundException;
}