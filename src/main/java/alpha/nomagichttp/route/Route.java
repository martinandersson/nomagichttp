package alpha.nomagichttp.route;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;

import java.util.Map;

/**
 * A {@code Route} represents the request-target from a request-line of an URI
 * coupled together with one or many request handlers that knows how to process
 * the request.<p>
 *
 * The request-target is also known as "a target resource upon which to apply
 * [...] semantics", i.e. handlers (
 * <a href="https://tools.ietf.org/html/rfc7230#section-5.1">RFC 7230 §5.1</a>).<p>
 * 
 * For example, given this request:<p>
 * <pre>{@code
 *   GET /hello.txt HTTP/1.1
 *   User-Agent: curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3
 *   Host: www.example.com
 *   Accept: text/plain;charset=utf-8
 * }</pre>
 *
 * The route "/hello.txt" is the request-target and can only match one
 * registered route for the server. Which one of the route's possibly many
 * handlers to handle the request is determined based on the method token - in
 * this example "GET" - and if present, the "Accept" and "Content-Type" headers.
 * Read more on this in the javadoc of {@linkplain RequestHandler}<p>
 * 
 * Routes are added to a {@link RouteRegistry registry} which is added to the
 * {@link HttpServer server}.<p>
 * 
 * When routing a request, the server will first find the route using {@link
 * RouteRegistry#lookup(String)}, then find the handler using {@link
 * Route#lookup(String, MediaType, MediaType[])}.<p>
 * 
 * The identity of all registered routes correspond to unique paths, or in other
 * words; unique joins of the route segments without taking parameters and
 * handlers into consideration. This means that all parameters in the request
 * are optional and they (or handlers) can not be used to differentiate routes
 * apart. The handler is still free to perform request argument validation and
 * respond accordingly.<p>
 * 
 * The identity-rule has many benefits. It's much more aligned with the HTTP
 * specification which defines a resource to which handlers and parameters are
 * semantics; icing on the cake. It's also a much more simple design and easy to
 * understand versus having to specify how exactly the semantics would
 * participate in the identity. Lastly, it has runtime benefits in the form of a
 * faster lookup (server can assume <i>any</i> match of a request-target is the
 * correct one) and there will be no late surprises in production due to
 * ambiguity (route registry builder fail-fast with a
 * {@link RouteCollisionException}).<p>
 * 
 * The implementation must be thread-safe.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see RouteBuilder
 */
public interface Route
{
    /**
     * Returns a match if this route matches the specified {@code requestTarget},
     * otherwise {@code null}.<p>
     * 
     * The request-target passed to this method must have the trailing query
     * part - if present - cut off.
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
     * @throws NullPointerException     if {@code method} is {@code null}
     * @throws NoHandlerFoundException  if no handler was found
     */
    RequestHandler lookup(String method, MediaType contentType, MediaType[] accepts) throws NoHandlerFoundException;
    
    /**
     * Returns the route identity.
     * 
     * @return the route identity
     * 
     * @see Route
     */
    String identity();
    
    /**
     * Returns a string representation of this route.<p>
     * 
     * Path parameter names will be enclosed within "/{}".<p>
     * 
     * For example, if route has segment "/A" + "my-param" + segment "/B", then
     * the returned String will be "/A/{my-param}/B".
     * 
     * @return a string representation of this route
     */
    @Override
    String toString();
    
    /**
     * Represents a match between a route and an inbound request-target.
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