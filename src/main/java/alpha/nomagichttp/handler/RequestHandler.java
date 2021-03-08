package alpha.nomagichttp.handler;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.route.AmbiguousNoHandlerFoundException;
import alpha.nomagichttp.route.HandlerCollisionException;
import alpha.nomagichttp.route.NoHandlerFoundException;
import alpha.nomagichttp.route.Route;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static alpha.nomagichttp.message.MediaType.ALL;
import static alpha.nomagichttp.message.MediaType.NOTHING;
import static alpha.nomagichttp.message.MediaType.NOTHING_AND_ALL;
import static alpha.nomagichttp.message.MediaType.TEXT_PLAIN;
import static alpha.nomagichttp.message.MediaType.parse;
import static alpha.nomagichttp.message.Responses.accepted;
import static java.util.Objects.requireNonNull;

/**
 * Holder of a request-to-response {@link #logic() function} (the "logic
 * instance") coupled together with metadata describing semantics of the
 * function.<p>
 * 
 * The metadata consists of a HTTP {@link #method() method} token and
 * {@link #consumes() consumes}/{@link #produces() produces} media types. This
 * information is only used as filters for a lookup algorithm when the server
 * has matched a request against a {@link Route} and needs to select which
 * handler of the route to process the request.<p>
 * 
 * The server applies no HTTP semantics to the message exchange once the handler
 * has been selected. The handler logic is in full control over how it
 * interprets the request headers- and body as well as what headers and body are
 * sent back.<p>
 * 
 * A {@code RequestHandler} can be built using {@link #builder(String)} or other
 * static methods found in {@link Builder} and {@link RequestHandlers}.
 * 
 * 
 * <h2>Handler Selection</h2>
 * 
 * When the server selects which handler of a route to call, it first weeds out
 * all handlers that does not qualify based on request headers and the handler's
 * metadata. If there's still many of them that qualifies, the handler with
 * media types preferred by the client and with greatest {@link
 * MediaType#specificity() specificity} will be used. More details will be
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
 * ambiguous. When the handler resolution ends ambiguously, a {@link
 * AmbiguousNoHandlerFoundException} is thrown<p>
 * 
 * It isn't possible to add completely equivalent handlers to a route as
 * this would immediately fail-fast with a {@link HandlerCollisionException}.<p>
 * 
 * To guard against ambiguity, the application can register a more generic
 * handler as a fallback. The most generic handler which can never be
 * eliminated as a candidate based on media types alone, consumes
 * {@link MediaType#NOTHING_AND_ALL} and produces "*&#47;*".<p>
 * 
 * For example:
 * <pre>{@code
 *     import static alpha.nomagichttp.handler.RequestHandler.Builder.GET;
 *     ...
 *     RequestHandler h = GET()
 *             .consumesNothingAndAll()
 *             .producesAll()
 *             .run(() -> System.out.println("Hello, World!"));
 * }</pre>
 * 
 * Or, use a utility method to accomplish the same thing:
 * 
 * <pre>{@code
 *     import static alpha.nomagichttp.handler.RequestHandlers.GET;
 *     ...
 *     RequestHandler h = GET().run(() -> System.out.println("Hello, World!"));
 * }</pre>
 * 
 * <h3>Qualify handler by method token</h3>
 * 
 * The first step for the server when resolving a handler is to lookup
 * registered handlers by using the case-sensitive method token from the
 * request-line.<p>
 * 
 * For example, this request would match all "GET" handlers of route
 * "/hello.txt":
 * <pre>{@code
 *   GET /hello.txt HTTP/1.1
 *   User-Agent: curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3
 *   Host: www.example.com
 *   Accept: text/plain;charset=utf-8
 * }</pre>
 * 
 * <h3>Qualify handler by consuming media type</h3>
 * 
 * If an inbound request has a {@link MediaType media type} set in the
 * "Content-Type" header, then this hints that an entity-body will be attached
 * and the server will proceed to filter out all handlers that does not consume
 * a {@link MediaType#compatibility(MediaType) compatible} media type.<p>
 * 
 * The handler can be very generic; ""text/*"", or the handler can be more
 * specific; "text/plain". In the event both of these handlers would remain after
 * the elimination process, the latter would be selected because he is the most
 * specific one.<p>
 * 
 * The handler may declare that he can only process requests that are
 * <i>missing</i> the "Content-Type" header by specifying {@link
 * MediaType#NOTHING}.<p>
 * 
 * The handler may declare that he can process any media type, as long as the
 * "Content-Type" header is provided in the request using {@link
 * MediaType#ALL} ("*&#47;*").<p>
 * 
 * The handler may declare that he doesn't care at all whether or not the
 * "Content-Type" is provided or what value it might have: {@link
 * MediaType#NOTHING_AND_ALL}.
 * 
 * <h3>Qualify handler with producing media type (proactive content negotiation)</h3>
 * 
 * The "Accept" header of a request indicates what media type(s) the client is
 * willing to accept as response body. Each such media type - or "media range"
 * to be technically correct - can carry with it a "quality" value indicating
 * the client's preference. It makes no sense for the handler to specify a
 * quality value.<p>
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
 * The "Accept" header could be missing, in which case it, per the HTTP
 * specification, defaults to "*&#47;*". I.e. the client is by default willing
 * to accept anything in response. The client has no means to indicate that a
 * response whatever it would be or even absent, is unacceptable.<p>
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
 * The server ignores other headers such as "Accept-Charset" and
 * "Accept-Language".<p>
 * 
 * There is no library-provided support for magical request parameters
 * ("?format=json") and so called "URL suffixes" ("/my-resource.json").
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
 * From the client's perspective on the other hand, even if the client has
 * specified parameters which couldn't be matched against no other handler than
 * one who implicitly handles all parameters, then it is still better to have a
 * response than none at all.<p>
 * 
 * When evaluating media type parameters, then all parameters must match; both
 * names and values. The order of parameters does not matter. Parameter names
 * are case-insensitive but almost all parameter values are case-sensitive. The
 * only exception is the "charset" parameter value for all "text/*" media types
 * which is treated case-insensitively.
 * 
 * 
 * <h2>Scopes</h2>
 * 
 * There is no library-provided scope mechanism. Normal rules concerning
 * reachability of Java references applies. Effectively, this means that the
 * implementation built using {@link #builder(String)} can be regarded as a
 * "singleton" or "application-scoped". A custom implementation can choose to
 * create a new logic instance for each request since the {@link #logic()}
 * method is invoked anew for each request.
 * 
 * 
 * <h2>Thread safety and object equality</h2>
 * 
 * The implementation is thread-safe, both the handler itself and the logic
 * instance it returns. The server will invoke the handler concurrently for
 * parallel inbound requests targeting the same handler.<p>
 * 
 * Equality of handlers is fully based on method tokens, consumes- and produces
 * media types. The logic instances plays no part.
 * 
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see ErrorHandler
 */
public interface RequestHandler
{
    /**
     * Creates a new {@code RequestHandler} builder.<p>
     * 
     * The method is any string, case-sensitive. For example, "GET" and
     * "POST". Constants are available in {@link HttpConstants.Method}.<p>
     * 
     * Builders with standardized methods is already available using static
     * methods in the builder interface, such as {@link Builder#GET()}, {@link
     * Builder#POST()} etc.
     * 
     * @param method token qualifier
     * 
     * @return a builder with the {@code method} set
     * 
     * @throws NullPointerException if {@code method} is {@code null}
     */
    // TODO: More strict definition of method; throw IllegalArgumentException if blank
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
     */
    String method();
    
    /**
     * Returns the media type of the request entity-body this handler
     * consumes.<p>
     * 
     * For example "*&#47;*", "text/plain".
     * 
     * @return the media type (never {@code null})
     * 
     * @see RequestHandler
     */
    MediaType consumes();
    
    /**
     * Returns the media type of the response entity-body this handler
     * produces.<p>
     * 
     * For example "*&#47;*", "text/plain".
     * 
     * @return the media type his handler produces (never {@code null})
     * 
     * @see RequestHandler
     */
    MediaType produces();
    
    /**
     * Returns the function that will process a request into a response.<p>
     * 
     * This method is called anew each time a route and handler has been matched
     * against a request.
     * 
     * @return the processing logic (never {@code null})
     * 
     * @see RequestHandler
     */
    Function<Request, CompletionStage<Response>> logic();
    
    /**
     * Returns this represented as a string.<p>
     * 
     * There's probably not much sense in marshalling the logic instance
     * together with the returned string given how the logic does not
     * participate in the identity of the handler.
     * 
     * @return this represented as a String (never {@code null})
     */
    @Override
    String toString();
    
    /**
     * Builder of {@link RequestHandler}.<p>
     * 
     * Each method returns an immutable builder instance which can be used
     * repeatedly as a template for new builds.<p>
     * 
     * The builder will guide the user through a series of steps along the
     * process of building a handler.<p>
     * 
     * The first step is the {@link RequestHandler#builder(String)} constructor
     * which requires an HTTP method. Static methods for HTTP-standardized
     * methods exists in the form of {@link #GET()}, {@link #POST()} and so
     * on.<p> 
     * 
     * The next step is to specify what media type the handler consumes followed
     * by what media type it produces, for example "text/plain".<p>
     * 
     * The last step will be to specify the {@link RequestHandler#logic() logic}
     * of the handler. The last step is also what builds a new handler
     * instance.<p>
     * 
     * Ultimately, the logic is a {@code Function<Request,
     * CompletionStage<Response>>}, but for convenience, the builder provides
     * some adapter methods which receives a variety of arguments adapted into
     * the final request-processing {@code Function}, accordingly:<p>
     * 
     * <strong>{@code respond()}</strong> receives an already built response
     * object which is immediately and indiscriminately returned for each
     * handled request. A great option for static resources!<p> 
     * 
     * <strong>{@code run()}</strong> receives a no-args {@link Runnable} which
     * represents logic that does not need to access the request object and has
     * no need to customize the "202 Accepted" response sent back to the client.
     * This flavor is useful for handlers that will accept all requests as a
     * command to initiate processes on the server.<p>
     * 
     * <strong>{@code accept()}</strong> is very much similar to {@code run()},
     * except the logic is represented by a {@link Consumer} who will receive
     * the request object and can therefore read meaningful data out of it.<p>
     * 
     * <strong>{@code supply()}</strong> receives a {@link Supplier} which
     * represents logic that is not interested in the request object but does
     * have the need to return a fully customizable response.<p>
     * 
     * <strong>{@code apply()}</strong> receives a {@link Function} which has
     * access to the request object <i>and</i> returns a fully customizable
     * response.<p>
     * 
     * The implementation is thread-safe.<p>
     * 
     * The implementation does not necessarily implement {@code hashCode()} and
     * {@code equals()}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    interface Builder
    {
        /**
         * Returns a builder with HTTP method set to "GET".
         * 
         * @return a builder with HTTP method set to "GET"
         */
        static Builder GET() {
            return builder("GET");
        }
        
        /**
         * Returns a builder with HTTP method set to "HEAD".
         * 
         * @return a builder with HTTP method set to "HEAD"
         */
        static Builder HEAD() {
            return builder("HEAD");
        }
        
        /**
         * Returns a builder with HTTP method set to "POST".
         * 
         * @return a builder with HTTP method set to "POST"
         */
        static Builder POST() {
            return builder("POST");
        }
        
        /**
         * Returns a builder with HTTP method set to "PUT".
         * 
         * @return a builder with HTTP method set to "PUT"
         */
        static Builder PUT() {
            return builder("PUT");
        }
        
        /**
         * Returns a builder with HTTP method set to "DELETE".
         * 
         * @return a builder with HTTP method set to "DELETE"
         */
        static Builder DELETE() {
            return builder("DELETE");
        }
        
        /**
         * Set consumption media type to {@link MediaType#NOTHING}.
         * 
         * @return the next step
         */
        default NextStep consumesNothing() {
            return consumes(NOTHING);
        }
        
        /**
         * Set consumption media type to {@link MediaType#ALL}.
         * 
         * @return the next step
         */
        default NextStep consumesAll() {
            return consumes(ALL);
        }
        
        /**
         * Set consumption media type to {@link MediaType#NOTHING_AND_ALL}.
         * 
         * @return the next step
         */
        default NextStep consumesNothingAndAll() {
            return consumes(NOTHING_AND_ALL);
        }
        
        /**
         * Set consumption media type to {@link MediaType#TEXT_PLAIN}.
         * 
         * @return the next step
         */
        default NextStep consumesTextPlain() {
            return consumes(TEXT_PLAIN);
        }
        
        /**
         * Parse and set consumption {@code mediaType}.
         * 
         * @return the next step
         * @param mediaType to set
         * @see MediaType#parse(CharSequence) 
         */
        default NextStep consumes(String mediaType) {
            return consumes(parse(mediaType));
        }
        
        /**
         * Set consumption media type.
         * 
         * @return the next step
         * @param mediaType to set
         * @throws NullPointerException if {@code mediaType} is {@code null}
         */
        NextStep consumes(MediaType mediaType);
        
        /**
         * An API specific for selecting producing media type qualifier.
         */
        interface NextStep
        {
            /**
             * Set producing media type to {@link MediaType#ALL}.
             * 
             * @return the last step
             */
            default LastStep producesAll() {
                return produces(ALL);
            }
            
            /**
             * Set producing media type to {@link MediaType#TEXT_PLAIN}.
             * 
             * @return the last step
             */
            default LastStep producesTextPlain() {
                return produces(TEXT_PLAIN);
            }
            
            /**
             * Parse and set producing {@code mediaType}.
             * 
             * @return the last step
             * @param mediaType to set
             * @see MediaType#parse(CharSequence)
             */
            default LastStep produces(String mediaType) {
                return produces(parse(mediaType));
            }
            
            /**
             * Set producing media type.
             * 
             * @return the last step
             * @param mediaType to set
             * @throws NullPointerException if {@code mediaType} is {@code null}
             */
            LastStep produces(MediaType mediaType);
        }
    
        /**
         * An API specific for specifying the request handler logic.
         */
        interface LastStep
        {
            /**
             * Delegate handler's logic to a static response.
             * 
             * @param response what each request should get in response
             * @return a new request handler
             * @throws NullPointerException if {@code response} is {@code null}
             * @see Builder
             */
            default RequestHandler respond(Response response) {
                return supply(response::completedStage);
            }
            
            /**
             * Delegate handler's logic to a runnable.
             * 
             * @param logic delegate
             * @return a new request handler
             * @throws NullPointerException if {@code logic} is {@code null}
             * @see Builder
             */
            default RequestHandler run(Runnable logic) {
                requireNonNull(logic);
                return accept(requestIgnored -> logic.run());
            }
            
            /**
             * Delegate handler's logic to a consumer of the request.
             *
             * @param logic delegate
             * @return a new request handler
             * @throws NullPointerException if {@code logic} is {@code null}
             * @see Builder
             */
            default RequestHandler accept(Consumer<Request> logic) {
                requireNonNull(logic);
                return apply(req -> {
                    logic.accept(req);
                    return accepted().completedStage();
                });
            }
            
            /**
             * Delegate handler's logic to a supplier of the response.
             *
             * @param logic delegate
             * @return a new request handler
             * @throws NullPointerException if {@code logic} is {@code null}
             * @see Builder
             */
            default RequestHandler supply(Supplier<CompletionStage<Response>> logic) {
                requireNonNull(logic);
                return apply(requestIgnored -> logic.get());
            }
            
            /**
             * Set handler's logic.
             *
             * @param logic to call
             * @return a new request handler
             * @throws NullPointerException if {@code logic} is {@code null}
             */
            RequestHandler apply(Function<Request, CompletionStage<Response>> logic);
        }
    }
}