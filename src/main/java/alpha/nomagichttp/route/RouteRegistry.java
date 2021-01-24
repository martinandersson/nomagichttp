package alpha.nomagichttp.route;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Request;

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
     * @see Route
     */
    void add(Route route);
    
    /**
     * Remove a route.<p>
     * 
     * The route's currently active requests and exchanges will run to
     * completion and will not be aborted. Only when all active connections
     * against the route have closed will the route effectively not be in use
     * anymore. However, the route is guaranteed to not be <i>discoverable</i>
     * for <i>new</i> lookup operations once this method has returned.<p>
     * 
     * In order for the route to be removed, the current route in the registry
     * occupying the same path position must be {@code equal} to the given route
     * using {@code Route.equals(Object)}. Currently, route equality is not
     * specified and the default implementation has not overridden the equals
     * method. I.e., the route provided must be the same instance.
     * 
     * @param route to remove
     * 
     * @return {@code true} if successful (route was added before), otherwise
     *         {@code false} (the route is unknown)
     * 
     * @throws NullPointerException if {@code route} is {@code null}
     */
    boolean remove(Route route);
    
    /**
     * Match the path segments from a request path against a route.<p>
     * 
     * The given segments must not be percent-decoded. Decoding is done by the
     * route registry implementation before comparing starts with the segments
     * of registered routes. Both non-decoded and decoded parameter values will
     * be accessible in the returned match object.<p>
     * 
     * Empty strings must be normalized away. The root "/" can be matched by
     * specifying an empty iterable.
     * 
     * @param pathSegments from request path (normalized but not percent-decoded)
     * 
     * @return a match (never {@code null})
     * 
     * @throws NullPointerException
     *             if {@code v} is {@code null}
     * 
     * @throws IllegalArgumentException
     *             if any encountered segment is the empty string
     * 
     * @throws NoRouteFoundException 
     *             if a route can not be found
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
        String pathParam(String name);
        
        /**
         * Equivalent to {@link Request.Parameters#pathRaw(String)}.
         */
        String pathParamRaw(String name);
    }
}