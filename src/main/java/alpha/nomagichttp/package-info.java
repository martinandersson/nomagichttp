/**
 * NoMagicHTTP is an asynchronous server-side library used to receive HTTP
 * requests and respond to them.<p>
 * 
 * The NoMagicHTTP library strives to offer an elegant and powerful API that is
 * just about as fast and scalable as any fully JDK-based HTTP server
 * implementation could possibly be.<p>
 *
 * Best of all, this library is designed around the firmly held opinion that all
 * forms of magic are evil. Annotations and "beans" will never be a part of the
 * library, only developer joy and productivity. (TODO: link to design document).
 * 
 * 
 * <h3>Architectural overview</h3>
 * 
 * A very central component is a {@link alpha.nomagichttp.route.Route Route}
 * (the "resource" or "request-target"), to which at least one request {@link
 * alpha.nomagichttp.handler.Handler Handler} must be added. The handler is
 * responsible for processing a {@link alpha.nomagichttp.message.Request
 * Request} into a {@link alpha.nomagichttp.message.Response Response}<p>
 * 
 * When processing a request, the handler will have an optionally complete
 * control over the bytes read from the inbound message body as well as the
 * response sent back (head + body). The message bodies are modelled as a
 * {@link java.util.concurrent.Flow.Publisher Flow.Publisher&lt;ByteBuffer&gt;},
 * consumed as request body and produced as response body respectively. This
 * enables the server implementation to be very scalable. Helpful API-provided
 * types and methods will make working in an asynchronous environment both easy
 * and fun.<p>
 * 
 * Once the route has been constructed it can be added to a {@link
 * alpha.nomagichttp.Server Server}. The default server implementation ({@link
 * alpha.nomagichttp.internal.AsyncServer AsyncServer}) does not use selector
 * threads or any other type of polling. It is completely non-blocking and
 * "proactive" for maximum performance across all operating systems that runs
 * Java.<p>
 * 
 * Routes can dynamically be added to and removed from a server. A legal server
 * variant is to not even have any routes registered. The idea is that resources
 * (what's "behind the route") can be short-lived and serve very specific
 * purposes, so their presence can change.<p>
 * 
 * Request handlers does not enjoy the same level of freedom. They can not
 * dynamically come and go from the route. If "hot swapping" out the logic
 * inside a route is really necessary, then the application has to provide it's
 * own handler implementation or reconstruct the route itself.<p>
 * 
 * {@code Route}, {@code Handler}, {@code Request}, {@code Response} and even
 * the {@code Server} are interfaces, of which any implementation can be used as
 * long as the contract is honored.<p>
 * 
 * This library provides <i>default</i> implementations, often built through a
 * <i>builder</i>. For example {@link alpha.nomagichttp.route.DefaultRoute
 * DefaultRoute}, built by {@link alpha.nomagichttp.route.RouteBuilder
 * RouteBuilder}. {@link alpha.nomagichttp.handler.DefaultHandler
 * DefaultHandler} built by {@link alpha.nomagichttp.handler.HandlerBuilder
 * HandlerBuilder}, and so forth.<p>
 * 
 * Commonly, there's also a convenient API on top of the builders meant for
 * static import in order to easily implement common use-cases, for example
 * {@link alpha.nomagichttp.handler.Handlers#GET() Handlers.GET()} and {@link
 * alpha.nomagichttp.message.Responses#ok() Responses.ok()}.<p>
 * 
 * TODO: Describe error handling
 * 
 * 
 * <h3>Examples</h3>
 * 
 * TODO: Provide
 */
package alpha.nomagichttp;