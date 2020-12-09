package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.RequestHandler;

/**
 * Utility method to construct the default implementation of {@link Route}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see RouteBuilder
 */
public final class Routes
{
    private Routes() {
        // Empty
    }
    
    /**
     * Builds a route with the specifies handlers registered.<p>
     * 
     * The route has no declared path parameters. For this need, use {@link
     * Route#builder(String)} directly.
     * 
     * @param path   route path, for example "/hello"
     * @param first  required handler
     * @param more   optionally more handlers
     * 
     * @return a route
     */
    public static Route route(String path, RequestHandler first, RequestHandler... more) {
        return Route.builder(path).handler(first, more).build();
    }
}