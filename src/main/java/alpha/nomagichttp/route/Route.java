package alpha.nomagichttp.route;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.SortedSet;

/**
 * A {@code Route} is "a target resource upon which to apply semantics"
 * (<a href="https://tools.ietf.org/html/rfc7230#section-5.1">RFC 7230 §5.1</a>).
 * It can be built using a {@link #builder(String)}. There's also a convenient
 * shortcut which both builds and add the route; {@link
 * HttpServer#add(String, RequestHandler, RequestHandler...)}.<p>
 * 
 * The route is associated with one or more <i>request handlers</i>. In HTTP
 * parlance, handlers are also known as different "representations" of the
 * resource. Which handler specifically to invoke for the request is determined
 * by qualifying metadata and specificity, as detailed in the Javadoc of {@link
 * RequestHandler}.<p>
 * 
 * A request will match exactly one or no route in the {@link RouteRegistry}
 * held by the HTTP server. Your users will not suffer from unintended surprises
 * and there are no complex and hard to understand priority rules to learn.
 * Attempting to add a route to a route registry which already has an
 * equivalent route registered will throw a {@link RouteCollisionException}.<p>
 * 
 * An example of a request (copy-pasted from
 * <a href="https://tools.ietf.org/html/rfc7230#section-5.3.1">RFC 7230 §5.3.1</a>):
 * <pre>{@code
 *   GET /where?q=now HTTP/1.1
 *   Host: www.example.com
 * }</pre>
 * 
 * The request-target "/where?q=now" has a path component and a query component.
 * The path "/where" will match a route which declares exactly one segment
 * "where". The query "?q=now" specifies a "q"-named parameter with the value
 * "now".<p>
 * 
 * The route may declare named path parameters which act like a wildcard segment
 * whose dynamic value is given by the client through the request path. Path-
 * and query parameters may be retrieved using {@link Request#parameters()}<p>
 * 
 * Path parameters come in two forms; single-segment and catch-all.<p>
 * 
 * Single-segment path parameters match anything until the next '/' or the path
 * end. They are denoted using the prefix ':'. A request path must carry a value
 * for the segment in order to match with a route that has declared a
 * single-segment path parameter. They can not be configured as optional.
 * 
 * <pre>
 *   Route registered: /user/:id
 *   
 *   Request path:
 *   /user/123            match, id = 123
 *   /user/foo            match, id = foo
 *   /user                no match (missing segment value)
 *   /user/foo/profile    no match (unknown segment "profile")
 * </pre>
 * 
 * Within the route registry, path parameters (both single-segment and
 * catch-all) are mutually exclusive for that segment position. For example, you
 * can not at the same time register a route {@code "/user/new"} and a route
 * {@code "/user/:id"}, or {@code "/user/:id"} and {@code "/user/:blabla"}.<p>
 * 
 * Static- and single segment path parameters may have any number of descendant
 * routes on the same hierarchical branch. In the following example, we register
 * three different routes in order to better guide the client:
 * <pre>
 *   /user                   respond "404 Bad Request, missing id"
 *   /user/:id               respond options available for the user, "file, ..."
 *   /user/:uid/file/:fid    respond specified user file
 * </pre>
 * 
 * The previous example also demonstrates that just because two or more routes
 * are located on the same hierarchical branch, the path parameter names they
 * declare may still be different. A path parameter name is only required to be
 * unique for a specific {@code Route} object. The last route could have just as
 * well been expressed as {@code "/user/:id/file/:fid"} and added to the same
 * registry. But, this route can never be constructed: {@code
 * "/user/:id/file/:id"} (duplicated name!)<p> 
 * 
 * Catch-all path parameters match everything until the path end. They must
 * therefore be the last segment defined. They are denoted using the prefix
 * '*'.<p>
 * 
 * Catch-all parameters are effectively optional since they match everything
 * from a given position, including nothing at all. For consistency, the
 * value when retrieved will always begin with a '/', even if the client didn't
 * provide a value in the request path. On the contrary, single-segment
 * parameter values will never begin with '/' as these are truncated from the
 * request path (see the subsequently documented normalization procedure).
 * 
 * <pre>
 *   Route registered: /src/*filepath
 * 
 *   /src                    match, filepath = "/"
 *   /src/                   match, filepath = "/"
 *   /src/subdir             match, filepath = "/subdir"
 *   /src/subdir/file.txt    match, filepath = "/subdir/file.txt"
 * </pre>
 *
 * It is possible to mix both parameter styles, e.g. {@code
 * "/:drive/*filepath"}.<p>
 * 
 * As previously noted, static- and single segment parameters can build a route
 * hierarchy. Or in other words, a route position may have a parent route. For
 * example, you can have {@code "/user"}, {@code "/user/:id"} and {@code
 * "/user/:id/avatar"} all registered at the same time in the same registry. But
 * this is not true for catch-all since it matches everything including no value
 * at all. You can not register {@code "/src"} and {@code "/src/*filepath"} in
 * the same registry at the same time. Failure to register a route with the
 * registry causes a {@link RouteCollisionException} to be thrown.<p>
 * 
 * Query parameters are always optional, they can not be used to distinguish one
 * route from another, nor do they affect how a request path is matched against
 * a route.<p>
 * 
 * In order to find a matching route, the following steps are applied to the
 * request path:
 * 
 * <ul>
 *   <li>Clustered forward slashes are reduced to just one. Empty segments are
 *       not supported and will consequently be discarded.</li>
 *   <li>All trailing forward slashes are truncated. A trailing slash is usually
 *       used to separate a file from a directory. However, "R" in URI stands
 *       for <i>resource</i>. Be that a file, directory, whatever - makes no
 *       difference.</li>
 *   <li>The empty path will be replaced with "/".</li>
 *   <li>The path is split into segments using the forward slash character as a
 *       separator.</li>
 *   <li>Each segment will be percent-decoded as if using {@link
 *       URLDecoder#decode(String, Charset) URLDecoder.decode(segment, StandardCharsets.UTF_8)}
 *       <i>except</i> the plus sign ('+') is <i>not</i> converted to a space
 *       character and remains the same (standard
 *       <a href="https://tools.ietf.org/html/rfc3986#section-2.1">RFC 3986</a> behavior).</li>
 *   <li>Dot-segments (".", "..") are normalized as defined by step 1 and 2 in
 *       Javadoc of {@link URI#normalize()} (basically "." is removed and ".."
 *       removes the previous segment, also see <a href="https://tools.ietf.org/html/rfc7231#section-9.1">
 *       RFC 7231 §9.1</a>)</li>
 *   <li>Finally, all remaining segments that are not interpreted as a path
 *       parameter value must match a route's segments exactly and in order. In
 *       particular, note that route-matching is case-sensitive and characters
 *       such as "+" and "*" has no special meaning, they will be compared
 *       literally.</li>
 * </ul>
 * 
 * The implementation is thread-safe and immutable. It does not necessarily
 * implement {@code hashCode()} and {@code equals()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see RouteRegistry
 * @see Route.Builder
 * @see Request.Parameters
 */
public interface Route
{
    /**
     * Returns a {@code Route} builder.<p>
     * 
     * The most simplest route that can be built is the root without path
     * parameters:
     * 
     * <pre>{@code
     *     Route r = Route.builder("/").handler(...).build();
     * }</pre>
     * 
     * Alternatively, import static {@code Routes.route()}, then:
     * <pre>{@code
     *     Route r = route("/", ...);
     * }</pre>
     * 
     * The value given to this method as well as {@link Builder#append(String)}
     * is a pattern that may declare many segments including path parameters, as
     * long as these are delimited using a '/'. The pattern will be split and
     * each element will be consumed as either a static segment or a path
     * parameter name. The pattern is a shortcut for using explicit builder
     * methods to accomplish the same result. All of these expressions builds a
     * route of the same path ({@code "/files/:user/*filepath"}):
     * 
     * <pre>{@code
     *    Route.builder("/").append("files").paramSingle("user").paramCatchAll("filepath")...
     *    Route.builder("/files").append(":user/*filepath")...
     *    Route.builder("/files/:user/*filepath")...
     * }</pre>
     * 
     * Technical jargon, just to have it stated: '/' serves as a segment
     * delimiter. Any leading or trailing '/' in the pattern will be discarded
     * (at most one) and thus never become part of a static segment value or
     * parameter name. Only the root segment may be the empty string. Clustered
     * '/' will throw an {@code IllegalArgumentException}. For details related
     * to individual components, see {@link Route.Builder}.
     * 
     * @param pattern to parse
     * 
     * @return a new builder
     * 
     * @throws NullPointerException
     *             if {@code pattern} is {@code null}
     * 
     * @throws RouteParseException
     *             if a static segment value is empty, or
     *             if parameter names are repeated in the pattern, or
     *             if a catch-all parameter is not the last segment
     */
    static Route.Builder builder(String pattern) {
        return new DefaultRoute.Builder(pattern);
    }
    
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
     * @throws MethodNotAllowedException
     *             if no handler matching the method can be found
     * @throws MediaTypeNotAcceptedException
     *             if no handler matching "Accept:" media type can be found
     * @throws MediaTypeUnsupportedException
     *             if no handler matching "Content-Type:" media type can be found
     * 
     * @see HttpConstants.Method
     * @see RequestHandler.Builder#builder(String) 
     */
    RequestHandler lookup(String method, MediaType contentType, MediaType[] accepts);
    
    /**
     * Returns all segments of this route.<p>
     * 
     * All routes are implicitly a descendant of the root which is never
     * returned from this method, i.e., an empty string. The segment value
     * follows the pattern specified in {@link #builder(String)}. For example,
     * ["files", ":user", "*filepath"].
     * 
     * @return all the segments of this route (unmodifiable)
     */
    Iterable<String> segments();
    
    /**
     * Returns all method tokens of all handlers registered with this route.
     * 
     * @return all method tokens of all handlers registered with this route (unmodifiable)
     */
    SortedSet<String> supportedMethods();
    
    /**
     * Returns '/' concatenated with '/'-joined {@link #segments()}.<p>
     * 
     * The string returned from this method can be feed to {@link
     * #builder(String)} in order to reconstruct a new route with a different
     * set of handlers.
     * 
     * @return joined segments
     */
    @Override
    String toString();
    
    /**
     * Builder of a {@link Route}.<p>
     * 
     * A valid static segment value can be any character sequence as long as it
     * is not empty and does not include a '/' (the slash will be interpreted by
     * {@link #builder(String)} and {@link #append(String)} as a separator).<p>
     * 
     * For example, a route can look like a cat emoji:
     * <pre>{@code
     *   Rout cat = Route.builder("/ (=^・・^=)").handler(...).build();
     * }</pre>
     * 
     * Parameter names can similarly be anything, as long as it is a unique name
     * for the route. The only purpose of the name is for the HTTP server to use
     * it as a key in a map.<p>
     * 
     * The pattern consuming methods will take (and remove) the first char - if
     * it is a ':' or '*' - as an indicator of the parameter type. The {@code
     * param***()} builder methods accept the given string at face value.
     * <pre>{@code
     *   Route.builder("/:user")                    Access using request.parameters().path("user")
     *   Route.builder("/").paramSingle('user')     Same as above
     *   Route.builder("/").paramSingle(':user')    WARNING! request.parameters().path(":user")
     *   Route.builder("/").paramSingle('/user')    WARNING! request.parameters().path("/user")
     * }</pre>
     * 
     * Please be mindful that pushing through weird parameter names by using an
     * explicit parameter method instead of a pattern may break the ability to
     * reconstruct the route by using its {@link #toString()} result as a
     * pattern for a new route.<p>
     * 
     * The builder is not thread-safe and is intended to be used as a throw-away
     * object. Each of the setter methods modifies the state of the builder and
     * returns the same instance. Modifying the builder after the route has been
     * built has undefined application behavior.<p>
     * 
     * The implementation does not necessarily implement {@code hashCode()} and
     * {@code equals()}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     * 
     * @see Route
     */
    interface Builder
    {
        /**
         * Declare a single-segment path parameter.
         * 
         * @param name of parameter
         * @return this (for chaining/fluency)
         * @throws NullPointerException if {@code name} is {@code null} 
         * @throws IllegalStateException if the same name has already been used
         */
        Route.Builder paramSingle(String name);
        
        /**
         * Declare a catch-all path parameter.<p>
         * 
         * No other segments or parameters may follow.
         * 
         * @param name of parameter
         * 
         * @return this (for chaining/fluency)
         * 
         * @throws NullPointerException
         *             if {@code name} is {@code null}
         * 
         * @throws IllegalStateException
         *             if the same name has already been used, or
         *             if a catch-all parameter has already been specified
         */
        Route.Builder paramCatchAll(String name);
        
        /**
         * Append another pattern.
         * 
         * @param pattern to append
         * 
         * @return this (for chaining/fluency)
         * 
         * @throws NullPointerException
         *             if {@code pattern} is {@code null}
         * 
         * @throws IllegalArgumentException
         *             if a static segment value is empty
         * 
         * @throws IllegalStateException
         *             if parameter names are repeated, or
         *             if a catch-all parameter is not the last segment
         * 
         * @see Route#builder(String) 
         */
        Route.Builder append(String pattern);
        
        /**
         * Add request handler(s).
         * 
         * @param first  first request handler
         * @param more   optionally more handlers
         * 
         * @throws HandlerCollisionException
         *             if an equivalent handler has already been added
         * 
         * @return this (for chaining/fluency)
         * 
         * @see RequestHandler
         */
        Route.Builder handler(RequestHandler first, RequestHandler... more);
        
        /**
         * Returns a new {@code Route} built from the current state of this
         * builder.
         * 
         * @return a new {@code Route}
         * 
         * @throws IllegalStateException if no handlers have been added
         */
        Route build();
    }
}