package alpha.nomagichttp.route;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.internal.DefaultServer;
import alpha.nomagichttp.message.Request;

/**
 * Provides thread-safe operations over a bunch of routes. Also known in other
 * corners of the internet as a "router".<p>
 * 
 * How routes are looked up depends very much on how they are stored, i.e. the
 * underlying data structure. The registry type encapsulates both store- and
 * retrieve.<p>
 * 
 * The {@link DefaultRouteRegistry} is constructed by {@code
 * HttpServer.create()} and passed to the {@link DefaultServer}. Registry-like
 * methods declared in the {@link HttpServer} interface are shortcuts that
 * delegate straight to the server's route registry instance.<p>
 * 
 * The registry can be replaced by a custom implementation of your choice,
 * simply create the {@code DefaultServer} manually. 
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
     * Remove a route.
     *
     * @param pattern of route to remove
     *
     * @return the route removed ({@code null} if non-existent)
     *
     * @throws IllegalArgumentException
     *             if a static segment value is empty
     *
     * @throws IllegalStateException
     *             if a catch-all parameter is not the last segment
     * 
     * @see HttpServer#remove(String) 
     */
    Route remove(String pattern);
    
    /**
     * Remove a route.
     * 
     * @param route to remove
     * 
     * @return {@code true} if successful (route was added before), otherwise
     *         {@code false} (the route is unknown)
     * 
     * @throws NullPointerException if {@code route} is {@code null}
     * 
     * @see HttpServer#remove(Route) 
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
         * 
         * @param name of path parameter (case sensitive)
         * @return the path parameter value (percent-decoded)
         */
        String pathParam(String name);
        
        /**
         * Equivalent to {@link Request.Parameters#pathRaw(String)}.
         * 
         * @param name of path parameter (case sensitive)
         * @return the raw path parameter value (not decoded/unescaped)
         */
        String pathParamRaw(String name);
    }
}