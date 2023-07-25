package alpha.nomagichttp.handler;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.event.EventEmitter;
import alpha.nomagichttp.event.ResponseSent;
import alpha.nomagichttp.message.Attributes;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.MediaTypeParseException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.AmbiguousHandlerException;
import alpha.nomagichttp.route.HandlerCollisionException;
import alpha.nomagichttp.route.NoHandlerResolvedException;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.util.ScopedValues;
import alpha.nomagichttp.util.Throwing;

import java.util.function.BiConsumer;

import static alpha.nomagichttp.message.MediaType.ALL;
import static alpha.nomagichttp.message.MediaType.NOTHING;
import static alpha.nomagichttp.message.MediaType.NOTHING_AND_ALL;
import static alpha.nomagichttp.message.MediaType.parse;

/**
 * A request-processing function dressed up with metadata.<p>
 * 
 * An inbound request carries with it an HTTP method token, which is just one
 * piece of the handler's metadata that the server uses to filter which handler
 * of a particular {@link Route} to execute. This token is specified when
 * creating a request handler {@link #builder(String) builder}. For convenience,
 * there are static builder methods for commonly used tokens; {@link #GET()},
 * {@link #POST()}, {@link #PUT()}, and so on.<p>
 * 
 * The request handler's {@code apply} method is expected to return a so-called
 * "final response", which the server will then write to the client.<p>
 * 
 * For example, responding 200 (OK):<p>
 * 
 * {@snippet :
 *   // @link substring="apply" target ="Builder#apply(Throwing.Function)" region
 *   RequestHandler textPlain
 *            // @link substring="text" target ="Responses#text(String)" :
  *           = GET().apply(requestIgnored -> text("Goodbye! Come again."));
 *   // @end
 * }
 * 
 * A final response may be preceded by any number of interim responses from
 * category 1XX (Informational) (since HTTP 1.1). For example, interim responses
 * can be used to send progress reports on a long-running job.<p>
 * 
 * {@snippet :
 *   // @link substring="processing" target="Responses#processing()" region
 *   // @link substring="toBuilder" target="Response#toBuilder()" region
 *   // @link substring="setHeader" target="Response.Builder#setHeader(String, String)" region
 *   // @link substring="build" target="Response.Builder#build()" region
 *   // @link substring="channel" target="ScopedValues#channel()" region
 *   // @link substring="write" target="ChannelWriter#write(Response)" region
 *   // @link substring="noContent" target="Responses#noContent()" region
 *   requestIgnored -> {
 *       // First we do some work, hypothetically
 *       ...
 *       
 *       // Send a 102 (Processing) update
 *       Response rsp = processing()
 *           .toBuilder().setHeader("Progress", "45%").build();
 *       channel().write(rsp);
 *       
 *       // At some point we're done and respond 204 (No Content)
 *       return noContent();
 *   }
 *   // @end
 *   // @end
 *   // @end
 *   // @end
 *   // @end
 *   // @end
 *   // @end
 * }
 * 
 * There are other ways to execute a long-running job. For example, the
 * application could do {@code Thread.startVirtualThread(myJob)}, and return a
 * job id to the client which the client then uses to poll the status from
 * another endpoint. Instead of creating the virtual thread explicitly, one can
 * use {@code ExecutorService.newVirtualThreadPerTask()}. Just keep in mind that
 * the service's {@code close()} method will <i>wait</i> for the task to
 * complete and thus stall the request handler invocation — so, generally not
 * recommended.<p>
 * 
 * Most request handlers will respond only one response; the same instance
 * returned from the {@code apply} method. If the application needs to execute
 * logic post-transmission of a response and this logic must execute within the
 * request handler, then writing the final response explicitly is an
 * alternative.<p>
 * 
 * {@snippet :
 *   request -> {
 *       channel().write(text("Secret data"));
 *       // User download succeeded, now we need to
 *       sendReportToGovernment(request);
 *       // And server has no need for a response anymore
 *       return null;
 *   }
 * }
 * 
 * If the post-transmission logic does not need to execute within the handler,
 * then an alternative is to subscribe to the {@link ResponseSent} event.<p>
 * 
 * {@snippet :
 *   // TODO: We need to fix the event API lol
 *   class Metrics {
 *       static void collect(
 *           ResponseSent thisStupidArgNeedsToGo,
 *           Response rsp,
 *           ResponseSent.Stats stats)
 *       {
 *           ...
 *       }
 *   }
 *   // @link substring="events" target="HttpServer#events()" region
 *   // @link substring="on" target="EventEmitter#on(Class, BiConsumer)" region
  *   // Somewhere else
 *   server.events().on(ResponseSent.class, Metrics::collect);
 *   // @end
 *   // @end
 * }
 * 
 * <h2>Handler Selection</h2>
 * 
 * The metadata is used as filters for the handler lookup algorithm.<p>
 * 
 * The metadata consists of a required HTTP {@link #method() method} token and
 * {@link #consumes() consumes}/{@link #produces() produces} media types. The
 * media types, if not specified, default to a wildcard sentinel that handles
 * any type.<p>
 * 
 * When the server selects which handler of a route to call, it first weeds out
 * all handlers that does not qualify based on request headers and the handler's
 * metadata. If there's still more than one handler that qualify, the handler
 * that handles media types preferred by the client and whose media types are
 * most {@link MediaType#specificity() specific} will be selected. More details
 * will be discussed throughout subsequent sections.<p>
 * 
 * A {@link NoHandlerResolvedException} is thrown if no handler can be
 * matched.<p>
 * 
 * Unfortunately, it is possible for an inbound request to be matched against
 * many handlers which are from the request's perspective an equally good fit.
 * For example, suppose the route has two handlers mapped to the "GET" method
 * that both consume "text/plain" and differ only in their producing media
 * type, then they would both match a "GET" request with header
 * "Content-Type: text/plain" and "Accept: *&#47;*". in this case, the matched
 * handlers are ambiguous. When the handler resolution ends ambiguously, an
 * {@link AmbiguousHandlerException} is thrown.<p>
 * 
 * It is not possible to add completely equivalent handlers to a route as
 * this would fail-fast with a {@link HandlerCollisionException}.<p>
 * 
 * To guard against ambiguity, the application can register a more generic
 * handler as a fallback. The most generic handler that can never be
 * eliminated as a candidate based on media types alone, consumes
 * {@link MediaType#NOTHING_AND_ALL} and produces "*&#47;*". These are also
 * the default media types used if not specified.<p>
 * 
 * Given this code:<p>
 * 
 * {@snippet :
 *   // The request's content headers will never eliminate this candidate
 *   Response textPlain = Responses.text("greeting=Hello");
 *   RequestHandler regularFallback
 *       = GET().apply(requestIgnored -> textPlain);
 *   
 *   // An acquired taste
 *   Response json = Responses.json("""
 *       {"greeting": "Hello"}""");
 *   RequestHandler specificJson
 *       = GET().produces("application/json")
 *              .apply(requestIgnored -> json);
 *   
 *   // Add-order does not matter
 *   server.add("/greeting", specificJson, regularFallback);
 * }
 * 
 * The result is:
 * 
 * <pre>
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
 * <h3>Qualify handler by consuming media type</h3>
 * 
 * If a request has a {@link MediaType} set in the {@value
 * HttpConstants.HeaderName#CONTENT_TYPE} header, then this indicates that a
 * request body is attached and the server will proceed to filter out all
 * handlers that does not consume a {@link MediaType#compatibility(MediaType)
 * compatible} media type.<p>
 * 
 * The handler can be very generic; "text/*", or the handler can be more
 * specific; "text/plain". In the event both of these handlers remain after
 * the elimination process, the latter would be selected because it is the most
 * specific one.<p>
 * 
 * The handler can declare that it can only process requests that are
 * <i>missing</i> the content-type header by specifying {@link
 * MediaType#NOTHING}.<p>
 * 
 * The handler can declare that it can process any media type, as long as the
 * header is provided in the request using {@link MediaType#ALL}
 * ("*&#47;*").<p>
 * 
 * The handler can declare that it doesn't care at all whether or not the header
 * is provided or what value it might have: {@link MediaType#NOTHING_AND_ALL}.
 * 
 * <h3>Qualify handler by producing media type (proactive content negotiation)</h3>
 * 
 * The {@value HttpConstants.HeaderName#ACCEPT} header of a request indicates
 * what media type(s) the client is willing to accept as response body. Each
 * such media type — or "media range" to be technically correct — can carry with
 * it a <i>quality</i> value indicating the client's preference.<p>
 * 
 * If the quality value is "0", then this means that the client absolutely does
 * <i>not</i> accept said media type and <i>all handlers producing this media
 * type will be eliminated</i>.<p>
 * 
 * For all other quality values, the client-accept is only a statement of the
 * client's preference. The server/handler is still free to chose what
 * representation it wants to send back to the client.<p>
 * 
 * A quality value above "0" is interpreted by the server as a client-provided
 * ordering mechanism and will be used to order handler candidates accordingly.
 * The quality value — if not specified — defaults to "1".<p> 
 * 
 * The accept header could be missing, in which case it — per the HTTP
 * specification — defaults to "*&#47;*". In other words; the client is by
 * default willing to accept any kind of representation in the response. The
 * client has no means to indicate that a response — whatever it would be or
 * even absent — is unacceptable.<p>
 * 
 * For the same reasons, a handler's producing media type can not be {@code
 * MediaType.NOTHING} or {@code MediaType.NOTHING_AND_ALL}. The most generic
 * handler's producing media type is "*&#47;*". This handler would still be free
 * to not produce a response body if it does not want to.<p>
 * 
 * Technically speaking, an "I accept nothing" media range could be declared as 
 * "*&#47;*; q=0". This, however, appears to be outside of what the quality
 * value and content-negotiation mechanism from the HTTP specification ever had
 * in mind. Further, it's hard to imagine what real-world benefit such a
 * media-range declaration would be.<p>
 * 
 * Just as is the case when evaluating the handler's consuming media type, a
 * handler with a more specific producing media type is preferred over one with
 * a less specific one.<p>
 * 
 * The server ignores headers such as {@value
 * HttpConstants.HeaderName#ACCEPT_CHARSET} and {@value
 * HttpConstants.HeaderName#ACCEPT_LANGUAGE}.<p>
 * 
 * There is no library-provided support for magical parameters with a special
 * meaning ("?format=json") and no support for so-called "URL suffixes"
 * ("/my-resource.json"). The handler is free to implement such branching.
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
 * handles all parameters, then it is better to get a representation rather than
 * none at all.<p>
 * 
 * When evaluating media type parameters, all parameters must match; both names
 * and values. The order of parameters does not matter. Parameter names are
 * case-insensitive but almost all parameter values are case-sensitive. The only
 * exception is the "charset" parameter value for all "text/*" media types which
 * is treated case-insensitively.
 * 
 * <h2>Storing state</h2>
 * 
 * Prefer passing method arguments. If not possible, then there's a few
 * options.<p>
 * 
 * Data that needs to be accessed throughout the HTTP exchange:<p>
 * 
 * {@snippet :
 *   // @link substring="attributes" target="Request#attributes()" region
 *   // @link substring="set" target="Attributes#set(String, Object)" region
 *   request.attributes().set("my-data", 123);
 *   // @end
 *   // @end
 * }
 * 
 * For data that needs to span multiple HTTP exchanges, see the JavaDoc of
 * {@link ScopedValues#channel()}.
 * 
 * <h2>Thread safety and object equality</h2>
 * 
 * The server will invoke the handler concurrently for parallel inbound requests
 * targeting the same handler, and so, the implementation is thread-safe.<p>
 * 
 * Equality is fully based on the method token, consumes- and produces media
 * types. The request-processing function plays no part.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see ErrorHandler
 */
public interface RequestHandler extends Throwing.Function<Request, Response, Exception>
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
     * space.
     * 
     * @param method token qualifier
     * 
     * @return a builder with the {@code method} set
     * 
     * @throws NullPointerException
     *             if {@code method} is {@code null}
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
     * The default implementation returns {@link MediaType#NOTHING_AND_ALL}.
     * 
     * @return the media type (never {@code null})
     * 
     * @see RequestHandler
     */
    default MediaType consumes() {
        return NOTHING_AND_ALL;
    }
    
    /**
     * Returns the media type of the response entity-body this handler
     * produces.<p>
     * 
     * For example "*&#47;*", "text/plain".
     * 
     * @implSpec
     * The default implementation returns {@link MediaType#ALL}.
     * 
     * @return the media type his handler produces (never {@code null})
     * 
     * @see RequestHandler
     */
    default MediaType produces() {
        return ALL;
    }
    
    /**
     * Builder of a {@code RequestHandler}.<p>
     * 
     * A builder can be created using static factories from the enclosing
     * class:<p>
     * 
     * {@snippet :
     *   // @link substring="GET" target="#GET()" :
     *   RequestHandler.Builder forGetRequests = RequestHandler.GET();
     * }
     * 
     * When the builder has been constructed, the HTTP method will already have
     * been set. What remains is to set consuming and producing media types
     * (optionally), and to specify the request-processing function.<p>
     * 
     * Consuming and producing media types will by default be
     * {@link MediaType#NOTHING_AND_ALL} and "*&#47;*" respectively, meaning
     * that unless more restrictive media types are set, the handler is willing
     * to serve all requests no matter the presence- or value of the request's
     * {@value HttpConstants.HeaderName#CONTENT_TYPE} and {@value
     * HttpConstants.HeaderName#ACCEPT} headers.<p>
     * 
     * The function provided to {@link #apply(Throwing.Function)} is a delegate,
     * which the builder's constructed handler instance delegates to. This
     * should be fine for most use cases. For more advanced use cases (for
     * example, hot-swapping the request-processing logic), one can create a
     * class that implements the interface:<p>
     * 
     * {@snippet :
     *   class MyProcessor implements Throwing.Function<Request, Response, Exception> {
     *       MyProcessor(My threadSafeDependencies) {
     *           ...
     *       }
     *       @Override
     *       public Response apply(Request req) {
     *           ...
     *       }
     *   }
     *   server.add("/", GET().apply(new MyProcessor(...)));
     * }
     * 
     * Or, if one wishes to skip the builder altogether:<p>
     * 
     * {@snippet :
     *   class MyEndpoint implements RequestHandler {
     *       MyEndpoint(My threadSafeDependencies) {
     *           ...
     *       }
     *       @Override
     *       public String method() {
     *           return "GET"; // Or use HttpConstants
     *       }
     *       @Override
     *       public Response apply(Request req) {
     *           ...
     *       }
     *   }
     *   HttpServer.create().add("/", new MyEndpoint(...));
     * }
     * 
     * State-modifying methods return the same builder instance invoked. The
     * builder is not thread-safe. It should be used only temporarily while
     * constructing a request handler, and then the builder reference should be
     * discarded.<p>
     * 
     * The implementation is not required to implement {@code hashCode} and
     * {@code equals}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    interface Builder
    {
        /**
         * Sets consumption media-type to {@link MediaType#NOTHING}.
         * 
         * @implSpec
         * The default implementation is equivalent to:
         * <pre>
         *   return consumes(MediaType.NOTHING)
         * </pre>
         * 
         * @return this (for chaining/fluency)
         */
        default Builder consumesNothing() {
            return consumes(NOTHING);
        }
        
        /**
         * Parses and sets consumption media-type.
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
         * @throws MediaTypeParseException
         *             if media type failed to {@linkplain MediaType#parse(String) parse}
         */
        default Builder consumes(String mediaType) {
            return consumes(parse(mediaType));
        }
        
        /**
         * Sets consumption media type.
         * 
         * @param mediaType to set
         * 
         * @return this (for chaining/fluency)
         * 
         * @throws NullPointerException
         *             if {@code mediaType} is {@code null}
         */
        Builder consumes(MediaType mediaType);
        
        /**
         * Parses and sets producing {@code mediaType}.
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
         * @throws MediaTypeParseException
         *             if media type failed to {@linkplain MediaType#parse(String) parse}
         */
        default Builder produces(String mediaType) {
            return produces(parse(mediaType));
        }
        
        /**
         * Sets producing media type.
         * 
         * @param mediaType to set
         * 
         * @return this (for chaining/fluency)
         * 
         * @throws NullPointerException
         *             if {@code mediaType} is {@code null}
         */
        Builder produces(MediaType mediaType);
        
        /**
         * Builds a request handler.
         * 
         * @param delegate the request-processing function
         * 
         * @return a new request handler
         * 
         * @throws NullPointerException
         *             if {@code delegate} is {@code null}
         */
        RequestHandler apply(Throwing.Function<Request, Response, ? extends Exception> delegate);
    }
}