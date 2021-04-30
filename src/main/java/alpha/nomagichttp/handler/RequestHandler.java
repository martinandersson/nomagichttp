package alpha.nomagichttp.handler;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.MediaTypeParseException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.route.AmbiguousNoHandlerFoundException;
import alpha.nomagichttp.route.HandlerCollisionException;
import alpha.nomagichttp.route.NoHandlerFoundException;
import alpha.nomagichttp.route.Route;

import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static alpha.nomagichttp.message.MediaType.__ALL;
import static alpha.nomagichttp.message.MediaType.__NOTHING;
import static alpha.nomagichttp.message.MediaType.__NOTHING_AND_ALL;
import static alpha.nomagichttp.message.MediaType.parse;
import static java.util.Objects.requireNonNull;

/**
 * Holder of a request-processing function coupled together with metadata
 * describing semantics of the function.<p>
 * 
 * The metadata consists of a HTTP {@link #method() method} token and
 * {@link #consumes() consumes}/{@link #produces() produces} media types. This
 * information is only used as filters for a lookup algorithm when the server
 * has matched a request against a {@link Route} and needs to select which
 * handler of the route to process the request.<p>
 * 
 * A {@code RequestHandler} can be built using {@link #builder(String)
 * builder(String method)}. Commonly used methods exist in the form of {@link
 * #GET()}, {@link #POST()}, {@link #PUT()} and so on.
 * 
 * <h2>Handler Selection</h2>
 * 
 * When the server selects which handler of a route to call, it first weeds out
 * all handlers that does not qualify based on request headers and handler
 * metadata. If there's still more than one handler that qualify, the handler
 * with media types preferred by the client and with media types most {@link
 * MediaType#specificity() specific} will be used. More details will be
 * discussed throughout subsequent sections.<p>
 * 
 * A {@link NoHandlerFoundException} is thrown if no handler can be matched.<p>
 * 
 * It is possible for an inbound request to be matched against many handlers
 * which are from the request's perspective an equally good fit. For example,
 * suppose the route has two handlers mapped to the "GET" method token which
 * both consume "text/plain" and differs only in their producing media type.
 * They would both match a "GET" request with header "Content-Type: text/plain"
 * and "Accept: *&#47;*". For this request, the matched handlers are
 * ambiguous. When the handler resolution ends ambiguously, an {@link
 * AmbiguousNoHandlerFoundException} is thrown<p>
 * 
 * It isn't possible to add completely equivalent handlers to a route as
 * this would fail-fast with a {@link HandlerCollisionException}.<p>
 * 
 * To guard against ambiguity, the application can register a more generic
 * handler as a fallback. The most generic handler which can never be
 * eliminated as a candidate based on media types alone, consumes
 * {@link MediaType#__NOTHING_AND_ALL} and produces "*&#47;*". These are also
 * the default media types used if not specified.<p>
 * 
 * Example:
 * <pre>
 *   // Requests' content headers never eliminate this candidate
 *   Response textPlain = Responses.text("greeting=Hello");
 *   RequestHandler fallback = GET().respond(textPlain);
 *   
 *   // Acquired taste
 *   Response json = Responses.json("{\"greeting\": \"Hello\"}");
 *   RequestHandler specific = GET().produces("application/json").respond(json); // Or use MediaType.APPLICATION_JSON
 *   
 *   server.add("/greeting", specific, fallback); // {@literal <}-- order does not matter
 *   
 *   // Result
 *   GET /greeting HTTP/1.1
 *   Host: www.example.com
 *   {@literal >} greeting=Hello
 *   
 *   GET /greeting HTTP/1.1
 *   Host: example.com
 *   Accept: application/json
 *   {@literal >} {"greeting": "Hello"}
 * </pre>
 * 
 * <h3>Qualify handler by method token</h3>
 * 
 * The first step for the server when resolving a handler is to lookup
 * registered handlers by using the case-sensitive method token from the
 * request-line.<p>
 * 
 * For example, this request would match all "GET" handlers of route
 * "/hello.txt":
 * <pre>
 *   GET /hello.txt HTTP/1.1
 *   User-Agent: curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3
 *   Host: www.example.com
 *   Accept: text/plain;charset=utf-8
 * </pre>
 * 
 * <h3>Qualify handler by consuming media type</h3>
 * 
 * If a request has a {@link MediaType} set in the {@value
 * HttpConstants.HeaderKey#CONTENT_TYPE} header, then this hints that an
 * entity-body will be attached and the server will proceed to filter out all
 * handlers that does not consume a {@link MediaType#compatibility(MediaType)
 * compatible} media type.<p>
 * 
 * The handler can be very generic; "text/*", or the handler can be more
 * specific; "text/plain". In the event both of these handlers remain after
 * the elimination process, the latter would be selected because it is the most
 * specific one.<p>
 * 
 * The handler may declare that he can only process requests that are
 * <i>missing</i> the content-type header by specifying {@link
 * MediaType#__NOTHING}.<p>
 * 
 * The handler may declare that he can process any media type, as long as the
 * header is provided in the request using {@link MediaType#__ALL}
 * ("*&#47;*").<p>
 * 
 * The handler may declare that he doesn't care at all whether or not the header
 * is provided or what value it might have: {@link MediaType#__NOTHING_AND_ALL}.
 * 
 * <h3>Qualify handler by producing media type (proactive content negotiation)</h3>
 * 
 * The {@value HttpConstants.HeaderKey#ACCEPT} header of a request indicates
 * what media type(s) the client is willing to accept as response body. Each
 * such media type - or "media range" to be technically correct - can carry with
 * it a "quality" value indicating the client's preference. It makes no sense
 * for the handler to specify a quality value.<p>
 * 
 * If the quality value is "0", then this means the client absolutely does
 * <i>not</i> accept said media type and <i>all handlers producing this media
 * type will be eliminated</i>.<p>
 * 
 * For all other quality values, the client-accept is only a statement of the
 * client's preference. The server/handler is still free to chose what
 * representation it wants to send back to the client.<p>
 * 
 * A quality value above "0" is interpreted by the server as a client-provided
 * ordering mechanism and will be used to order handler candidates accordingly.
 * The quality value - if not specified - defaults to "1".<p> 
 * 
 * The accept header could be missing, in which case it, per the HTTP
 * specification, defaults to "*&#47;*". I.e. the client is by default willing
 * to accept any representation in the response. The client has no means to
 * indicate that a response whatever it would be or even absent, is
 * unacceptable.<p>
 * 
 * For the same reasons, a handler's producing media type can not be {@code
 * MediaType.NOTHING} or {@code MediaType.NOTHING_AND_ALL}. The most generic
 * handler's producing media type is "*&#47;*". This handler would still be free
 * to not produce a response body if it doesn't want to.<p>
 * 
 * Technically speaking, an "I accept nothing" media range could be declared as 
 * "*&#47;*; q=0". This however appears to be outside of what the quality value
 * and content-negotiation mechanism from the HTTP specification ever had in
 * mind. Further, it's hard to imagine what real-world benefit such a
 * media-range declaration would bring.<p>
 * 
 * Just as is the case when evaluating the handler's consuming media type, a
 * handler with a more specific producing media type is preferred over one with
 * a less specific one.<p>
 * 
 * The server ignores other headers such as {@value
 * HttpConstants.HeaderKey#ACCEPT_CHARSET} and {@value
 * HttpConstants.HeaderKey#ACCEPT_LANGUAGE}.<p>
 * 
 * There is no library-provided support for magical request parameters
 * ("?format=json") and so called "URL suffixes" ("/my-resource.json"). The
 * handler is of course free to implement such branching if desired.
 * 
 * <h3>Media type parameters</h3>
 * 
 * Media type parameters are only evaluated if they are specified on the
 * handler-side where they act like a filter; they must all match. A handler
 * that does not declare media type parameters implicitly handles any
 * combination of parameters including none at all.<p>
 * 
 * Adding parameters increases the handler's specificity.<p>
 * 
 * This makes it possible to be very specific and narrow down what requests a
 * handler handles and possibly have other handlers be more generic;
 * consuming/producing any combination of parameters.<p>
 * 
 * For example, if client sends a request with a Content-Type/Accept value
 * "text/plain; charset=utf-8", then this matches a handler consuming/producing
 * "text/plain; charset=utf-8" and it also matches a handler consuming/producing
 * "text/plain". If both of these handlers are registered with the route, then
 * the former would be chosen because it's the most specific one.<p>
 * 
 * A request that specifies "text/plain" will not match a handler that specifies
 * "text/plain; charset-utf-8". Adding parameters on the handler's side is
 * semantically the same as declaring an absolute requirement for what the
 * handler is able to handle.<p>
 * 
 * From the client's perspective, even if the request has specified parameters
 * which could not be matched against no other handler than one who implicitly
 * handles all parameters, then it is better to get a representation back rather
 * than none at all.<p>
 * 
 * When evaluating media type parameters, then all parameters must match; both
 * names and values. The order of parameters does not matter. Parameter names
 * are case-insensitive but almost all parameter values are case-sensitive. The
 * only exception is the "charset" parameter value for all "text/*" media types
 * which is treated case-insensitively.
 * 
 * <h2>Scopes</h2>
 * 
 * There is no library-provided scope mechanism. Normal rules concerning
 * reachability of Java references apply. Effectively, this means that the
 * handler instance may be regarded as a "singleton" or "application-scoped". A
 * custom implementation can choose to create a new logic instance for each
 * request since the {@link #logic()} method is invoked anew for each request.
 * Examples on how to implement this interface is provided in the JavaDoc of
 * {@link Builder}.<p>
 * 
 * Data that needs to be saved and accessed throughout the HTTP exchange can be
 * put as {@link Request#attributes()} and data that needs to span all HTTP
 * exchanges throughout the connection's life can be put as
 * {@link ClientChannel#attributes()}.
 * 
 * <h2>Thread safety and object equality</h2>
 * 
 * The implementation is thread-safe, both the handler itself and the logic
 * instance it returns. The server will invoke the handler concurrently for
 * parallel inbound requests targeting the same handler.<p>
 * 
 * Equality of handlers is fully based on the method token, consumes- and
 * produces media types. The logic instance plays no part.
 * 
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see ErrorHandler
 */
public interface RequestHandler
{
    /**
     * Returns a builder with HTTP method set to "GET".
     *
     * @return a builder with HTTP method set to "GET"
     */
    static Builder GET() {
        return builder(HttpConstants.Method.GET);
    }
    
    /**
     * Returns a builder with HTTP method set to "POST".
     *
     * @return a builder with HTTP method set to "POST"
     */
    static Builder POST() {
        return builder(HttpConstants.Method.POST);
    }
    
    /**
     * Returns a builder with HTTP method set to "PUT".
     *
     * @return a builder with HTTP method set to "PUT"
     */
    static Builder PUT() {
        return builder(HttpConstants.Method.PUT);
    }
    
    /**
     * Returns a builder with HTTP method set to "DELETE".
     *
     * @return a builder with HTTP method set to "DELETE"
     */
    static Builder DELETE() {
        return builder(HttpConstants.Method.DELETE);
    }
    
    /**
     * Returns a builder with HTTP method set to "HEAD".
     *
     * @return a builder with HTTP method set to "HEAD"
     */
    static Builder HEAD() {
        return builder(HttpConstants.Method.HEAD);
    }
    
    /**
     * Returns a builder with HTTP method set to "TRACE".
     *
     * @return a builder with HTTP method set to "TRACE"
     */
    static Builder TRACE() {
        return builder(HttpConstants.Method.TRACE);
    }
    
    /**
     * Creates a new {@code RequestHandler} builder.<p>
     * 
     * The method is any non-empty, case-sensitive string containing no white
     * space.<p>
     * 
     * @param method token qualifier
     * 
     * @return a builder with the {@code method} set
     * 
     * @throws NullPointerException
     *             if {@code method} is {@code null}
     * 
     * @throws IllegalArgumentException
     *             if {@code method} is empty or contains whitespace
     */
    static Builder builder(String method) {
        return new DefaultRequestHandler.Builder(method);
    }
    
    /**
     * Returns the handler's method token qualifier.<p>
     * 
     * For example "GET and "POST".
     * 
     * @return the method token (never {@code null})
     * 
     * @see RequestHandler
     * @see HttpConstants.Method
     * @see #builder(String) 
     */
    String method();
    
    /**
     * Returns the media type of the request entity-body this handler
     * consumes.<p>
     * 
     * For example "*&#47;*", "text/plain".
     * 
     * @implSpec
     * The default implementation returns {@link MediaType#__NOTHING_AND_ALL}.
     * 
     * @return the media type (never {@code null})
     * 
     * @see RequestHandler
     */
    default MediaType consumes() {
        return __NOTHING_AND_ALL;
    }
    
    /**
     * Returns the media type of the response entity-body this handler
     * produces.<p>
     * 
     * For example "*&#47;*", "text/plain".
     * 
     * @implSpec
     * The default implementation returns {@link MediaType#__ALL}.
     * 
     * @return the media type his handler produces (never {@code null})
     * 
     * @see RequestHandler
     */
    default MediaType produces() {
        return __ALL;
    }
    
    /**
     * Returns the function that will process a request into a response.<p>
     * 
     * No argument passed to the function is null. The channel argument must be
     * used at some point to either shutdown the channel or write a response.<p>
     * 
     * This method is called anew each time a route and handler has been matched
     * against a request.
     * 
     * @return the processing logic (never {@code null})
     * 
     * @see RequestHandler
     */
    BiConsumer<Request, ClientChannel> logic();
    
    /**
     * Builder of {@link RequestHandler}.<p>
     * 
     * When the builder has been constructed, the request handler method will
     * have already been set. What remains is to optionally set consuming and
     * producing media types and finally build the handler.<p>
     * 
     * Consuming and producing media types will by default be set to {@link
     * MediaType#__NOTHING_AND_ALL} and "*&#47;*" respectively, meaning that
     * unless more restrictive media types are set, the handler is willing to
     * serve all requests no matter the presence- or value of the request's
     * {@value HttpConstants.HeaderKey#CONTENT_TYPE} and {@value
     * HttpConstants.HeaderKey#ACCEPT} headers.<p>
     * 
     * The handler is built and returned from the setter method that specifies
     * the request-processing logic. Three different styles of setter methods
     * for the logic exist;
     * <i>{@code respond}</i>, <i>{@code apply}</i> and <i>{@code accept}</i>.
     * The first two are adapters that repackages the given function into the
     * {@code BiConsumer} type required by {@code accept}.<p>
     * 
     * {@code respond()} is great when the function does not need access to the
     * request object:
     * <pre>
     *   RequestHandler static = GET().respond(text("Hello!"));
     * </pre>
     * 
     * {@code apply()} is great when the function need access to the request
     * object and produces an asynchronous response (the request body is the
     * asynchronous part in this example which returns a {@code
     * CompletionStage}):
     * <pre>
     * 
     *   RequestHandler greeter = POST()
     *           .apply(request -{@literal >} request.body().toText()
     *                   .thenApply(name -{@literal >} text("Hello " + name + "!")));
     * </pre>
     * 
     * For any other case, or simply for the sake of code readability and
     * explicitness, {@code accept()} is given the undressed handler logic
     * function which must use the client channel to write a response:
     * <pre>
     * 
     *   RequestHandler greeter = POST().accept((request, channel) -{@literal >} {
     *       if (request.body().isEmpty()) {
     *           Response bad = Responses.badRequest();
     *           channel.write(bad);
     *       } else {
     *           CompletionStage{@literal <}Response{@literal >} ok
     *                   = request.body().convert(...).thenApply(...);
     *           channel.write(ok);
     *       }
     *   });
     * </pre>
     * 
     * Small JavaDoc examples are great, but most real-world endpoints will
     * likely be classes that implement the functional signature. This will also
     * be necessary for more advanced use-cases that needs hot-swapping,
     * request-scoped dependencies, and so forth.
     * <pre>
     * 
     *   class MyLogic implements BiConsumer{@literal <}Request, ClientChannel{@literal >} {
     *       MyLogic(My dependencies) {
     *           ...
     *       }
     *       public void accept(Request req, ClientChannel ch) {
     *           ...
     *       }
     *   }
     *   server.add("/", GET().accept(new MyLogic(...)));
     * </pre>
     * 
     * Or, if you wish to skip the builder completely:
     * <pre>
     * 
     *   class MyEndpoint implements RequestHandler {
     *       MyEndpoint(My dependencies) {
     *           ...
     *       }
     *       public String method() {
     *           return "GET"; // Or use HttpConstants
     *       }
     *       public BiConsumer{@literal <}Request, ClientChannel{@literal >} logic() {
     *           return this::process;
     *       }
     *       private void process(Request req, ClientChannel ch) {
     *           ...
     *       }
     *   }
     *   HttpServer.create().add("/", new MyEndpoint(...));
     * </pre>
     * 
     * State-modifying methods return the same builder instance invoked. The
     * builder is not thread-safe. It should be used only temporarily while
     * constructing a request handler and then the builder reference should be
     * discarded.<p>
     * 
     * The implementation does not not necessarily implement {@code hashCode()}
     * and {@code equals()}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    interface Builder
    {
        /**
         * Set consumption media-type to {@link MediaType#__NOTHING},
         * 
         * @implSpec
         * The default implementation is equivalent to:
         * <pre>
         *   return consumes(MediaType.__NOTHING)
         * </pre>
         * 
         * @return this (for chaining/fluency)
         */
        default Builder consumesNothing() {
            return consumes(__NOTHING);
        }
        
        /**
         * Parse and set consumption media-type.
         * 
         * @param mediaType to set
         * 
         * @implSpec
         * The default implementation is equivalent to:
         * <pre>
         *   return consumes(MediaType.parse(mediaType))
         * </pre>
         * 
         * @return this (for chaining/fluency)
         * 
         * @throws NullPointerException
         *             if {@code mediaType} is {@code null}
         * 
         * @throws MediaTypeParseException
         *             if media type failed to {@linkplain MediaType#parse(CharSequence) parse}
         */
        default Builder consumes(String mediaType) {
            return consumes(parse(mediaType));
        }
        
        /**
         * Set consumption media type.
         * 
         * @param mediaType to set
         * @return this (for chaining/fluency)
         * @throws NullPointerException if {@code mediaType} is {@code null}
         */
        Builder consumes(MediaType mediaType);
        
        /**
         * Parse and set producing {@code mediaType}.
         * 
         * @param mediaType to set
         * 
         * @implSpec
         * The default implementation is equivalent to:
         * <pre>
         *   return produces(MediaType.parse(mediaType))
         * </pre>
         * 
         * @return this (for chaining/fluency)
         * 
         * @throws NullPointerException
         *             if {@code mediaType} is {@code null}
         * 
         * @throws MediaTypeParseException
         *             if media type failed to {@linkplain MediaType#parse(CharSequence) parse}
         */
        default Builder produces(String mediaType) {
            return produces(parse(mediaType));
        }
        
        /**
         * Set producing media type.
         * 
         * @param mediaType to set
         * @return this (for chaining/fluency)
         * @throws NullPointerException if {@code mediaType} is {@code null}
         */
        Builder produces(MediaType mediaType);
        
        /**
         * Build a request handler that returns the given response to any valid
         * request hitting the route.
         * 
         * @param response to return
         * 
         * @implSpec
         * The default implementation is equivalent to:
         * <pre>
         *   Objects.requireNonNull(response);
         *   return accept((req, ch) -{@literal >} ch.write(response));
         * </pre>
         * 
         * @return a new request handler
         * 
         * @throws NullPointerException if {@code response} is {@code null}
         */
        default RequestHandler respond(Response response) {
            requireNonNull(response);
            return accept((req, ch) -> ch.write(response));
        }
        
        /**
         * Build a request handler that returns the given response to any valid
         * request hitting the route.
         * 
         * @param response to return
         * 
         * @implSpec
         * The default implementation is equivalent to:
         * <pre>
         *   Objects.requireNonNull(response);
         *   return accept((req, ch) -{@literal >} ch.write(response));
         * </pre>
         * 
         * @return a new request handler
         * 
         * @throws NullPointerException if {@code response} is {@code null}
         */
        default RequestHandler respond(CompletionStage<Response> response) {
            requireNonNull(response);
            return accept((req, ch) -> ch.write(response));
        }
        
        /**
         * Build a request handler that returns a response to any valid
         * request hitting the route.<p>
         * 
         * Unlike the other two <i>respond</i> overloads, this response is
         * retrieved lazily.
         * 
         * @param response supplier
         * 
         * @implSpec
         * The default implementation is equivalent to:
         * <pre>
         *   Objects.requireNonNull(response);
         *   return accept((req, ch) -{@literal >} ch.write(response.get()));
         * </pre>
         * 
         * @return a new request handler
         * 
         * @throws NullPointerException if {@code response} is {@code null}
         */
        default RequestHandler respond(Supplier<CompletionStage<Response>> response) {
            requireNonNull(response);
            return accept((req, ch) -> ch.write(response.get()));
        }
        
        /**
         * Build a request handler that invokes the given function for every
         * request and writes the produced response on the client channel.
         * 
         * @param logic to call
         * 
         * @implSpec
         * The default implementation is equivalent to:
         * <pre>
         *   Objects.requireNonNull(logic);
         *   return accept((req, ch) -{@literal >} ch.write(logic.apply(req)));
         * </pre>
         * 
         * @return a new request handler
         * 
         * @throws NullPointerException if {@code logic} is {@code null}
         */
        default RequestHandler apply(Function<Request, CompletionStage<Response>> logic) {
            requireNonNull(logic);
            return accept((req, ch) -> ch.write(logic.apply(req)));
        }
        
        /**
         * Build a request handler using the given logic function.<p>
         *
         * The function must ensure that a response is written to the client
         * channel at some point, or otherwise closed.
         * 
         * @param logic to call
         * @return a new request handler
         * @throws NullPointerException if {@code logic} is {@code null}
         */
        RequestHandler accept(BiConsumer<Request, ClientChannel> logic);
    }
}