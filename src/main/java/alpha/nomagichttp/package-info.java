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
 * A central component is a {@link alpha.nomagichttp.route.Route Route} (the
 * "resource" or "request-target"), to which at least one {@link
 * alpha.nomagichttp.handler.RequestHandler RequestHandler} must be added. The
 * handler is responsible for processing a {@link
 * alpha.nomagichttp.message.Request Request} into a {@link
 * alpha.nomagichttp.message.Response Response}<p>
 * 
 * Once the route has been built it can be added to an {@link
 * alpha.nomagichttp.HttpServer HttpServer}. The default server implementation
 * does not use selector threads (event polling) or any other type of blocking
 * techniques. It responds to native system events with zero blocking for
 * maximum performance across all operating systems that runs Java.<p>
 * 
 * Routes can dynamically be added to and removed from a server. A legal server
 * variant is to not even have any routes registered. The idea is that resources
 * (what's "behind the route") can be short-lived and serve very specific
 * purposes, so their presence can change.<p>
 * 
 * Request handlers does not enjoy the same level of freedom. They can not
 * dynamically come and go from the route. If "hot swapping" out the logic
 * inside a route is really necessary, then the application has to provide it's
 * own handler implementation or reconstruct the route.<p>
 * 
 * Entities such as {@code RequestHandler} and {@code Response} are often built
 * using a builder, retrievable from a static method, for example {@link
 * alpha.nomagichttp.handler.RequestHandler#builder(java.lang.String)
 * RequestHandler.builder()}. The builder gives a fine-grained control over
 * the build. Commonly, there's also a convenient API meant for static import in
 * order to easily implement common use-cases, for example {@link
 * alpha.nomagichttp.handler.RequestHandlers#GET() RequestHandlers.GET()} and
 * {@link alpha.nomagichttp.message.Responses#ok() Responses.ok()}.
 * 
 * 
 * <h3>Examples</h3>
 * 
 * See package {@link alpha.nomagichttp.examples}.
 */
package alpha.nomagichttp;