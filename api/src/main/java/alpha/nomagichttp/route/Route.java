package alpha.nomagichttp.route;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;

import java.net.URI;
import java.util.Collection;
import java.util.stream.Stream;

/**
 * Is "a target resource upon which to apply semantics" (
 * <a href="https://tools.ietf.org/html/rfc7230#section-5.1">RFC 7230 §5.1</a>
 * ).<p>
 * 
 * The route is associated with one or more <i>request handlers</i>. In HTTP
 * parlance, handlers are also known as "representations" of the resource. Which
 * handler specifically to invoke for the request is determined by qualifying
 * metadata and specificity, as detailed in the Javadoc of {@link
 * RequestHandler}.<p>
 * 
 * A {@code Route} can be built using {@link #builder(String)}. For convince,
 * the {@code HttpServer} has a method that both builds and adds the route at
 * the same time;
 * {@link HttpServer#add(String, RequestHandler, RequestHandler...)}.<p>
 * 
 * An inbound request will match exactly zero or one route in the
 * {@link RouteRegistry} held by the HTTP server. Attempting to add a route to a
 * route registry which already has an equivalent route registered will throw a
 * {@link RouteCollisionException}.<p>
 * 
 * Example request:
 * <pre>{@code
 *   GET /where?q=now HTTP/1.1
 *   Host: www.example.com
 * }</pre>
 * 
 * The string "/where?q=now" is a request-target, composed of a path component
 * and a query component. The path "/where" will match a route which declares
 * exactly one segment "where". The query "?q=now" specifies a "q"-named
 * parameter with the value "now".<p>
 * 
 * The route may declare named path parameters which act like a wildcard segment
 * whose dynamic value is given by the client through the request path. Path-
 * and query parameters may be retrieved using {@link Request#target()}<p>
 * 
 * Path parameters come in two forms; <i>single-segment</i> and
 * <i>catch-all</i>.<p>
 * 
 * Single-segment path parameters match anything until the next '/' or the path
 * end. They are denoted using the prefix ':'. All text following the prefix is
 * the name/key by which the value is acquired through the request object. An
 * inbound request path must contain a value for the segment in order to match
 * with a route that has declared a single-segment path parameter (they can not
 * be configured as optional).
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
 * Non-static segments are mutually exclusive for that position in the registry.
 * For example, one can not at the same time register a route {@code
 * "/user/john"} and a route {@code "/user/:name"}, or {@code "/user/:name"} and
 * {@code "/user/:id"}. This is by design as a request must match exactly one
 * route or none at all. Branching based on dynamic segment values must be
 * implemented by the route's handler(s).<p>
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
 * registry. But, this route could not also have been added: {@code
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
 * As previously noted, static- and single-segment parameters can build a route
 * hierarchy. For example, one can have {@code "/user"}, {@code "/user/:id"} and
 * {@code "/user/:id/avatar"} all registered at the same time in the same
 * registry. But this is not true for catch-all since it matches everything
 * including no value at all. One can not register {@code "/src"} and {@code
 * "/src/*filepath"} in the same registry at the same time.<p>
 * 
 * There is no support for specifying parameters of a segment, for example,
 * "/user;v=2". This segment would be matched literally against registered
 * routes; characters like ";" and "=" have no special meaning. The example
 * could instead have been modelled as "/v2/user".<p>
 * 
 * Query parameters are always optional (can not be configured as required),
 * they can not be used to distinguish one route from another, nor do they
 * affect how a request path is matched against a route.<p>
 * 
 * To find a matching route, the following steps are applied to the request
 * path:
 * 
 * <ul>
 *   <li>Clustered forward slashes are reduced to just one. Empty segments are
 *       not supported and will be discarded.</li>
 *   <li>All trailing forward slashes are truncated. A trailing slash is usually
 *       used to separate a file from a directory. However, "R" in URI stands
 *       for <i>resource</i>. Be that a file, directory, whatever - makes no
 *       difference.</li>
 *   <li>The empty path will be replaced with "/".</li>
 *   <li>The path is split into segments using the forward slash character as a
 *       separator.</li>
 *   <li>Each segment will be percent-decoded (
 *       <a href="https://datatracker.ietf.org/doc/html/rfc3986#section-2.1">RFC 3986 §2.1</a>).</li>
 *   <li>Dot-segments (".", "..") are normalized as defined by step 1 and 2 in
 *       Javadoc of {@link URI#normalize()} (basically "." is removed and ".."
 *       removes the previous segment, also see <a href="https://tools.ietf.org/html/rfc7231#section-9.1">
 *       RFC 7231 §9.1</a>)</li>
 *   <li>Finally, all remaining segments that are not interpreted as a path
 *       parameter value must match a route's segments exactly (case-sensitive)
 *       and in order.</li>
 * </ul>
 * 
 * The implementation is thread-safe and immutable. It does not necessarily
 * implement {@code hashCode()} and {@code equals()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see RouteRegistry
 * @see Route.Builder
 * @see Request.Target
 */
public interface Route
{
    /**
     * Returns a {@code Route} builder.<p>
     * 
     * The simplest route that can be built is the root without path parameters:
     * 
     * <pre>{@code
     *     RequestHandler handler = ...
     *     Route r = Route.builder("/").handler(handler).build();
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
     * As per the first example, using the builder makes it possible to avoid
     * embedding syntax-driven tokens in favor of explicit {@code paramXXX()}
     * method calls. If the application declares a route using one single
     * pattern string, as in the last example, then there's a method available
     * in the {@code HttpServer} interface that accomplishes the same thing:
     * <pre>
     *   RequestHandler handler = ...
     *   HttpServer server = ...
     *   server.{@link HttpServer#add(String, RequestHandler, RequestHandler...) add
     *     }("/files/:user/*filepath", handler);
     * </pre>
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
     * @throws RoutePatternInvalidException
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
     * @param method      method ("GET", "POST", ...)
     * @param contentType "Content-Type: " header value (may be {@code null})
     * @param accepts     "Accept: " header values (may be {@code null} or empty)
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
    RequestHandler lookup(String method, MediaType contentType, Collection<MediaType> accepts);
    
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
     * {@return unique method tokens of all handlers registered with this route}
     */
    Stream<String> supportedMethods();
    
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
     * Parameter names can similarly be anything (including the empty string),
     * as long as it is a unique name for the route. The only purpose of the
     * name is for the HTTP server to use it as a key in a map.<p>
     * 
     * The pattern-consuming methods will take (and remove) the first char — if
     * it is a ':' or '*' — as an indicator of the parameter type. The {@code
     * paramXXX()} builder methods accept the given string at face value.
     * <pre>{@code
     *   Route.builder("/:user")                    Access using request.target().pathParam("user")
     *   Route.builder("/").paramSingle('user')     Same as above
     *   Route.builder("/").paramSingle(':user')    WARNING! request.target().pathParam(":user")
     *   Route.builder("/").paramSingle('/user')    WARNING! request.target().pathParam("/user")
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