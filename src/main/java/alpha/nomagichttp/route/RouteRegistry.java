package alpha.nomagichttp.route;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;

/**
 * Provides thread-safe add- and remove operations over a bunch of routes. Also
 * known in other corners of the internet as a "router".<p>
 * 
 * The default implementation is similar in nature to many other router
 * implementations. The internally used data structure is a concurrent tree with
 * great performance characteristics.<p>
 * 
 * How routes are matched and the details of the pattern used to register routes
 * have been documented in the JavaDoc of {@link Route}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface RouteRegistry
{
    /**
     * Add a route.
     * 
     * @param  route to add
     * @return the HttpServer (for chaining/fluency)
     * 
     * @throws NullPointerException
     *             if {@code route} is {@code null}
     * 
     * @throws RouteCollisionException
     *             if an equivalent route has already been added
     * 
     * @see Route
     */
    HttpServer add(Route route);
    
    /**
     * Build a route and add it to the server.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>
     *     Route r = {@link Route}.{@link Route#builder(String)
     *               builder}(pattern).{@link Route.Builder#handler(RequestHandler, RequestHandler...)
     *               handler}(first, more).{@link Route.Builder#build()
     *               build}();
     *     return {@link #add(Route) add}(r);
     * </pre>
     * 
     * @param pattern of route path
     * @param first   request handler
     * @param more    optionally more request handlers
     * 
     * @return the HttpServer (for chaining/fluency)
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * 
     * @throws RouteParseException
     *             if a static segment value is empty, or
     *             if parameter names are repeated in the pattern, or
     *             if a catch-all parameter is not the last segment
     * 
     * @throws HandlerCollisionException
     *             if not all handlers are unique
     * 
     * @throws RouteCollisionException
     *             if an equivalent route has already been added
     */
    default HttpServer add(String pattern, RequestHandler first, RequestHandler... more) {
        Route r = Route.builder(pattern).handler(first, more).build();
        return add(r);
    }
    
    /**
     * Remove any route on the given hierarchical position.<p>
     * 
     * This method is similar to {@link #remove(Route)}, except any route no
     * matter its identity found at the hierarchical position will be removed.
     * The pattern provided is the same path-describing pattern provided to
     * methods such as {@link #add(String, RequestHandler, RequestHandler...)}
     * and {@link Route#builder(String)}, except path parameter names can be
     * anything, they simply do not matter. Other than that, the pattern will go
     * through the same normalization and validation routine.<p>
     * 
     * For example:
     * <pre>
     *   Route route = ...
     *   server.add("/download/:user/*filepath", route);
     *   server.remove("/download/:/*"); // or "/download/:bla/*bla", doesn't matter
     * </pre>
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
     */
    Route remove(String pattern);
    
    /**
     * Remove a route of a particular identity.<p>
     * 
     * The route's currently active exchanges will run to completion and will
     * not be aborted. Only when all of the exchanges have finished will the
     * route effectively not be in use anymore. However, the route is guaranteed
     * to not be <i>discoverable</i> for <i>new</i> requests once this method
     * has returned.<p>
     * 
     * In order for the route to be removed, the current route in the registry
     * occupying the same hierarchical position must be {@code equal} to the
     * given route using {@code Route.equals(Object)}. Currently, route equality
     * is not specified and the default implementation has not overridden the
     * equals method. I.e., the route provided must be the same instance.<p>
     * 
     * In order to remove <i>any</i> route at the targeted position, use {@link
     * #remove(String)} instead.
     * 
     * @param route to remove
     * 
     * @return {@code true} if successful, otherwise {@code false}
     * 
     * @throws NullPointerException if {@code route} is {@code null}
     */
    boolean remove(Route route);
}