package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.RequestHandler;

/**
 * Utility method to construct the default implementation of {@link Route}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route.Builder
 */
public final class Routes
{
    private Routes() {
        // Empty
    }
    
    /**
     * Builds a route with the specifies handlers registered.<p>
     * 
     * @param pattern  feed to {@link Route#builder(String)}
     * @param first    required handler
     * @param more     optionally more handlers
     * 
     * @return a route
     */
    public static Route route(String pattern, RequestHandler first, RequestHandler... more) {
        return Route.builder(pattern).handler(first, more).build();
    }
}