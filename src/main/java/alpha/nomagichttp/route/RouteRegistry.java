package alpha.nomagichttp.route;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Request;

import java.util.Optional;

/**
 * Provides thread-safe operations over a group of routes. Also known in other
 * corners of the internet as a "router".<p>
 * 
 * This type is constructed internally by the {@link HttpServer}. Once the
 * server has been built, the registry can be retrieved using {@link
 * HttpServer#getRouteRegistry()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route
 */
public interface RouteRegistry
{
    /**
     * Add a route.
     * 
     * @param route to add
     * 
     * @throws NullPointerException
     *           if {@code route} is {@code null}
     * 
     * @throws RouteCollisionException
     *           if an equivalent route has already been added
     * 
     * @throws AmbiguousRouteCollisionException
     *           if an effectively equivalent route has already been added
     * 
     * @see Route
     */
    void add(Route route);
    
    /**
     * Remove a route.<p>
     * 
     * The route's currently active requests and exchanges will run to
     * completion and will not be aborted. Only when all active connections
     * against the route have closed will the route effectively not be in use
     * anymore. However, the route will not be <i>discoverable</i> for new
     * lookup operations once this method has returned.
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
     * Remove a route using a specified route identity.
     * 
     * @param id route identity
     * 
     * @return the route if successful (route was added before), otherwise
     *         {@code null} (the route is unknown)
     * 
     * @throws NullPointerException if {@code routeIdentity} is {@code null}
     * 
     * @see Route
     */
    Route remove(String id);
    
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
    @Deprecated // use lookup(Iterable<String>) instead
    Route.Match lookup(String requestTarget);
    
    /**
     * Match the path segments from a request path against a route.<p>
     * 
     * Note: The given segments are not percent-decoded. This is done by the
     * route registry implementation before comparing starts with the segments
     * of registered routes. Both non-decoded and decoded parameter values will
     * be accessible in the returned match object.
     * 
     * @param pathSegments from request path (normalized but not percent-decoded)
     * 
     * @return a match (never {@code null})
     * 
     * @throws NullPointerException
     *             if {@code v} is {@code null}
     * 
     * @throws NoRouteFoundException 
     *             if a route can not be found
     * 
     * @throws AmbiguousRouteCollisionException
     *             if more than one route match the segments (this is truly
     *             exceptional and should be regarded as a bug; {@link
     *             RouteRegistry#add(Route) failed to fail-fast})
     */
    Match lookup(Iterable<String> pathSegments);
    
    /**
     * A match of a route.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    interface Match
    {
        /**
         * Returns the matched route.
         *
         * @return the matched route (never {@code null})
         */
        Route route();
        
        /**
         * Equivalent to {@link Request.Parameters#path(String)}.
         */
        Optional<String> pathParam(String name);
        
        /**
         * Equivalent to {@link Request.Parameters#pathRaw(String)}.
         */
        Optional<String> pathParamRaw(String name);
    }
}