package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;

import java.util.Map;

/**
 * A {@code Route} is "a target resource upon which to apply semantics"
 * (<a href="https://tools.ietf.org/html/rfc7230#section-5.1">RFC 7230 §5.1</a>).
 * It can be built using a {@link #builder()} or other static methods found in
 * {@link Routes}.<p>
 * 
 * The route is associated with one or more <i>request handlers</i>. In HTTP
 * parlance, handlers are also known as different "representations" of the
 * resource. Which handler specifically to invoke for the request is determined
 * by qualifying metadata and specificity, as detailed in the javadoc of {@link
 * RequestHandler}.<p>
 * 
 * Suppose the HTTP server receives this request (
 * <a href="https://tools.ietf.org/html/rfc7230#section-5.3.1">RFC 7230 §5.3.1</a>):<p>
 * <pre>{@code
 *   GET /where?q=now HTTP/1.1
 *   Host: www.example.com
 * }</pre>
 * 
 * The request-target "/where?q=now" has a <i>path</i> component and a query
 * component. The path "/where" will match a route in the HTTP server with the
 * same identity. The query "?q=now" specifies one "q"-named parameter with
 * value "now". The value can be retrieved using {@link
 * Request#paramFromQuery(String)}.<p>
 * 
 * The route may declare named path parameters that acts like a dynamic
 * segment whose value is given by the client through the path and retrievable
 * using {@link Request#paramFromPath(String) request}.<p>
 * 
 * Both query- and path parameters are optional and they can not be specified
 * as required. The request handler is free to interpret the presence, absence
 * and value of parameters however it sees fit. A path parameter value will be
 * assumed to end with a space- or forward slash character ('/').<p>
 * 
 * The route identity starts with a forward slash and consists of all its
 * segments joined without path parameter names. For example - using curly
 * braces ("{}") syntax for notation purposes only - this route:
 * 
 * <pre>
 *   /users/{user-id}/items/{item-id}
 * </pre>
 * 
 * Has identity {@code "/users/items"}. It will become the match for all of the
 * following request paths:
 * 
 * <pre>
 *   /users/items
 *   /users/123/items
 *   /users/items/456
 *   /users/123/items/456
 * </pre>
 * 
 * The only difference being whether or not parameter values are present in the
 * request object.<p>
 * 
 * Route collision- and ambiguity is detected at build-time and will fail-fast.
 * For example, the route {@code "/where/{param}"} can not be added to an HTTP
 * server which already has {@code "/where"} registered. This would crash with a
 * {@link RouteCollisionException}. TODO: Add ref to AmbiguousRouteCollisionException<p>
 * 
 * The implementation is thread-safe.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route.Builder
 * @see RouteRegistry
 */
public interface Route
{
    /**
     * Returns a match if this route matches the specified {@code requestTarget},
     * otherwise {@code null}.<p>
     * 
     * If there is no such route registered with the HTTP server, a {@link
     * NoRouteFoundException} is thrown, which is translated by {@link
     * ErrorHandler#DEFAULT} into a "404 Not Found" response.<p>
     * 
     * The request-target passed to this method must have the trailing query
     * part - if present - cut off.<p>
     * 
     * The HTTP server does not interpret the fragment part and it is undefined
     * whether or not it is included as part of the given request-target. The
     * fragment "is dereferenced solely by the user agent" (<a
     * href="https://tools.ietf.org/html/rfc3986#section-3.5">RFC 3986 §3.5</a>)
     * and so shouldn't have been sent to the server in the first place.
     * 
     * @param requestTarget  request-target
     * 
     * @return a match if this route matches the specified {@code requestTarget},
     *         otherwise {@code null}
     * 
     * @see RequestHandler
     */
    Match matches(String requestTarget);
    
    /**
     * Lookup a handler given a specified {@code method} and media types.
     * 
     * @param method       method ("GET", "POST", ...)
     * @param contentType  "Content-Type: " header value (may be {@code null})
     * @param accepts      "Accept: " header values (may be {@code null} or empty)
     * 
     * @return the handler
     * 
     * @throws NullPointerException
     *             if {@code method} is {@code null}
     * 
     * @throws NoHandlerFoundException
     *             if no handler matching the criteria can be found
     */
    RequestHandler lookup(String method, MediaType contentType, MediaType[] accepts) throws NoHandlerFoundException;
    
    /**
     * Returns the route identity.
     * 
     * @return the route identity (never {@code null} or empty)
     * 
     * @see Route
     */
    String identity();
    
    /**
     * Returns all segments joined with named parameter values.<p>
     * 
     * Path parameter names will be enclosed within "/{}".<p>
     * 
     * For example, if route has segment "/A" + "my-param" + segment "/B", then
     * the returned String will be "/A/{my-param}/B".
     * 
     * @return all segments joined with named parameter values
     */
    @Override
    String toString();
    
    /**
     * A route matched against a request.
     */
    interface Match {
        /**
         * Returns the matched route.<p>
         * 
         * The returned reference is the same object as the one invoked to
         * produce the match.
         * 
         * @return the matched route
         */
        Route route();
        
        /**
         * Returns path parameters which have been extracted from the
         * request-target.<p>
         * 
         * The returned map is empty if the route has no path parameters
         * declared or none was provided in the request-target.<p>
         * 
         * The returned map is unmodifiable.
         * 
         * @return path parameters (never null)
         */
        Map<String, String> parameters();
    }
}