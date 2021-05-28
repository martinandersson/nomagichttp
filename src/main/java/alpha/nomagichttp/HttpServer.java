package alpha.nomagichttp;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.internal.DefaultServer;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalBodyException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.DefaultRouteRegistry;
import alpha.nomagichttp.route.HandlerCollisionException;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteCollisionException;
import alpha.nomagichttp.route.RouteParseException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

import static java.net.InetAddress.getLoopbackAddress;

/**
 * Listens on a port for HTTP requests.<p>
 * 
 * This interface declares static <i>{@code create}</i> methods that construct
 * and return the default implementation {@link DefaultServer}. Once the server
 * has been constructed, it needs to <i>{@code start()}</i>.<p>
 * 
 * Routes can be dynamically added and removed using {@link #add(Route)} and
 * {@link #remove(Route)}. A legal server variant is to not even have any routes
 * registered. The idea is that resources (what's "behind the route") can be
 * short-lived and serve very specific purposes, so their presence can
 * change. Example:
 * 
 * <pre>
 *   HttpServer.{@link #create(ErrorHandler...)
 *     create}().{@link #add(String, RequestHandler, RequestHandler...)
 *       add}("/", {@link RequestHandler#GET()
 *         GET}().{@link RequestHandler.Builder#respond(Response)
 *           respond}({@link Responses#text(String)
 *             text}("Hello"))).{@link #start() start}();
 * </pre>
 * 
 * The server's function is to provide port- and channel management, parse
 * an inbound request head and resolve which handler of a route is qualified to
 * handle the request. Once the handler has been invoked, it has almost total
 * freedom in regards to how it interprets the request headers- and body as well
 * as what headers and body it responds.<p>
 * 
 * The process of receiving a request and respond responses (any number of
 * intermittent responses, followed by a final response) is often called an
 * "exchange".
 * 
 * <h2>Server Life-Cycle</h2>
 * 
 * It is possible to start many server instances on different ports. One
 * use-case for this pattern is to expose public endpoints on one port but keep
 * more sensitive administrator endpoints on another more secluded port.<p>
 * 
 * If at least one server is running, then the JVM will not shutdown when the
 * main application thread dies. For the application process to end, all
 * server instances must {@link #stop()}.<p>
 * 
 * The server may be recycled, i.e. started anew after having been stopped, any
 * number of times.
 * 
 * <h2>Supported HTTP Versions</h2>
 *
 * Currently, the NoMagicHTTP server is a project in its infancy. Almost
 * complete support for HTTP/1.0 and 1.1 is the first milestone, yet to be done
 * (see POA.md in repository). HTTP/2 will be implemented thereafter. HTTP
 * clients older than HTTP/1.0 is rejected (the exchange will crash with a
 * {@link HttpVersionTooOldException}.
 * 
 * <h2>HTTP message semantics</h2>
 * 
 * Only a very few message variants are specified to <i>not</i> have a body and
 * will be rejected by the server if they do ({@link IllegalBodyException}):
 * 
 * <ul>
 *   <li>{@link HttpConstants.Method#TRACE TRACE} requests (
 *     <a href="https://tools.ietf.org/html/rfc7231#section-4.3.8">RFC 7231 §4.3.8</a>)</li>
 *   <li>Responses to {@link HttpConstants.Method#HEAD HEAD} (
 *     <a href="https://tools.ietf.org/html/rfc7231#section-4.3.2">RFC 7231 §4.3.8</a>)
 *     and {@link HttpConstants.Method#CONNECT CONNECT} (
 *     <a href="https://tools.ietf.org/html/rfc7231#section-4.3.6">RFC 7231 §4.3.6</a>)</li>
 *   <li>Responses with a 1XX (Informational) {@link HttpConstants.StatusCode status code} (
 *     <a href="https://tools.ietf.org/html/rfc7231#section-6.2">RFC 7231 §6.2</a>)</li>
 * </ul>
 * 
 * These variants <i>must</i> be rejected since including a body would have
 * likely killed the protocol.<p>
 * 
 * For all other variants of requests and responses, the body is optional and
 * the server does not reject the message based on the presence of a body. This
 * is mostly true for all other message variants as well; the server does not
 * have an opinionated view unless warranted. The request handler is largely in
 * control over how it interprets the request message and what response it
 * returns.<p>
 * 
 * For example, it might not be common but it <i>is</i>
 * possible (and legit) for {@link HttpConstants.Method#GET GET} requests (
 * <a href="https://tools.ietf.org/html/rfc7231#section-4.3.1">RFC 7231 §4.3.1</a>
 * ) to have a body and for {@link HttpConstants.Method#POST POST} responses to
 * not have a body (
 * <a href="https://tools.ietf.org/html/rfc7230#section-3.3.2">RFC 7230 §3.3.2</a>
 * ). Similarly, the {@link HttpConstants.StatusCode#TWO_HUNDRED_ONE 201
 * (Created)} response often do have a body which "typically describes and links
 * to the resource(s) created" (
 * <a href="https://tools.ietf.org/html/rfc7231#section-6.3.2">RFC 7231 §6.3.2</a>
 * ), but it's not required to. And so the list goes on.
 * 
 * <h2>Thread Safety and Threading Model</h2>
 * 
 * The server is fully thread-safe, and mostly, asynchronous and
 * non-blocking. This is mostly true for the entire library API. It's actually
 * quite hard to screw up when programming with the NoMagicHTTP server.<p>
 * 
 * Life-cycle methods {@code start} and {@code stop} may block temporarily.<p>
 * 
 * The HttpServer API also functions as a route registry, to which we {@code
 * add} and {@code remove} routes. These methods are highly concurrent but may
 * impose minuscule blocks at the discretion of the implementation. Most
 * importantly, looking up a route - as is done on every inbound request - never
 * blocks and features great performance no matter the size of the registry.<p>
 * 
 * All servers running in the same JVM share a common pool of threads (aka
 * "request threads"). The pool handles I/O completion events and executes
 * application-provided entities such as the request- and error handlers. The
 * pool size is fixed and set to the value of {@link Config#threadPoolSize()}.
 * <p>
 * 
 * It is <strong>absolutely crucial</strong> that the application does not block
 * a request thread, for example by synchronously waiting on an I/O result. The
 * request thread is suitable only for short-lived and CPU-bound work. I/O work
 * or long-lived tasks should execute somewhere else. Blocking the request
 * thread will have a negative impact on scalability and could at worse starve
 * the pool of available threads making the server unable to make progress with
 * tasks such as accepting new client connections or processing other
 * requests.<p>
 * 
 * This is bad:
 * <pre>
 *   RequestHandler h = GET().{@link RequestHandler.Builder#accept(BiConsumer)
 *         accept}((request, channel) -{@literal >} {
 *       String data = database.fetch("SELECT * FROM Something"); // {@literal <}-- blocks!
 *       Response resp = {@link Responses}.text(data);
 *       channel.{@link ClientChannel#write(CompletionStage)
 *         write}(resp); // {@literal <}-- never blocks. Server is fully asynchronous.
 *   });
 * </pre>
 * 
 * Instead, do this:
 * <pre>
 * 
 *   RequestHandler h = GET().accept((request, channel) -{@literal >} {
 *       CompletionStage{@literal <}String{@literal >} data = database.fetchAsync("SELECT * FROM Something");
 *       CompletionStage{@literal <}Response{@literal >} resp = data.thenApply(Responses::text);
 *       channel.write(resp);
 *   });
 * </pre>
 * 
 * The problem is <i>not</i> synchronously producing a response <i>if</i> one
 * can be produced without blocking.
 * 
 * <pre>
 * 
 *   RequestHandler h = GET().accept((request, channel) -{@literal >} {
 *       Response resp = text(String.join(" ", "Short-lived", "CPU-bound work", "is fine!"));
 *       channel.write(resp);
 *   });
 * </pre>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route
 * @see RequestHandler
 * @see ErrorHandler
 */
public interface HttpServer
{
    /**
     * Create a server using {@linkplain Config#DEFAULT default
     * configuration}.<p>
     * 
     * The provided array of error handlers will be copied as-is. The
     * application should make sure that the array does not contain duplicates,
     * unless for some bizarre reason it is desired to have an error handler
     * called multiple times.
     * 
     * @param eh error handler(s)
     * 
     * @return an instance of {@link DefaultServer}
     * 
     * @throws NullPointerException
     *             if {@code eh} or an element therein is {@code null}
     */
    static HttpServer create(ErrorHandler... eh) {
        return create(Config.DEFAULT, eh);
    }
    
    /**
     * Create a server.<p>
     *
     * The provided array of error handlers will be copied as-is. The
     * application should make sure that the array does not contain duplicates,
     * unless for some bizarre reason it is desired to have an error handler
     * called multiple times.
     * 
     * @param config of server
     * @param eh     error handler(s)
     * 
     * @return an instance of {@link DefaultServer}
     * 
     * @throws NullPointerException
     *             if any argument or array element is {@code null}
     */
    static HttpServer create(Config config, ErrorHandler... eh) {
        return new DefaultServer(config, new DefaultRouteRegistry(), eh);
    }
    
    /**
     * Listen for client connections on a system-picked port on the loopback
     * address (IPv4 127.0.0.1, IPv6 ::1).<p>
     * 
     * This method is useful for inter-process communication on the same machine
     * or to start a server in a test environment.<p>
     * 
     * The port can be retrieved using {@link
     * #getLocalAddress()}{@code .getPort()}.<p>
     * 
     * Production code ought to specify an address using any other overload of
     * the start method.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>
     *   InetAddress addr = InetAddress.getLoopbackAddress();
     *   int port = 0;
     *   SocketAddress local = new InetSocketAddress(addr, port);
     *   return {@link #start(SocketAddress) start}(local);
     * </pre>
     * 
     * @return this (for chaining/fluency)
     * 
     * @throws IllegalStateException if the server is already running
     * @throws IOException if an I/O error occurs
     * 
     * @see InetAddress
     */
    default HttpServer start() throws IOException {
        return start(new InetSocketAddress(getLoopbackAddress(), 0));
    }
    
    /**
     * Listen for client connections on the specified port on the wildcard
     * address.<p>
     * 
     * The wildcard address is also known as "any local address" and "the
     * unspecified address".
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>
     *     return {@link #start(SocketAddress) start}(new InetSocketAddress(port));
     * </pre>
     * 
     * @param port to use
     * 
     * @return this (for chaining/fluency)
     * 
     * @throws IllegalStateException if server is already running
     * @throws IOException if an I/O error occurs
     * 
     * @see InetAddress
     */
    
    default HttpServer start(int port) throws IOException  {
        return start(new InetSocketAddress(port));
    }
    
    /**
     * Listen for client connections on a given hostname and port.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>
     *     return {@link #start(SocketAddress) start}(new InetSocketAddress(hostname, port));
     * </pre>
     * 
     * @param hostname to use
     * @param port to use
     * 
     * @return this (for chaining/fluency)
     * 
     * @throws IllegalStateException if server is already running
     * @throws IOException if an I/O error occurs
     * 
     * @see InetAddress
     */
    default HttpServer start(String hostname, int port) throws IOException {
        return start(new InetSocketAddress(hostname, port));
    }
    
    /**
     * Listen for client connections on a given address.
     * 
     * @param address to use
     * 
     * @return this (for chaining/fluency)
     * 
     * @throws NullPointerException if {@code address} is {@code null}
     * @throws IllegalStateException if server is already running
     * @throws IOException if an I/O error occurs
     *
     * @see InetAddress
     */
    HttpServer start(SocketAddress address) throws IOException;
    
    /**
     * Stop listening for client connections and do not begin new HTTP
     * exchanges.<p>
     * 
     * The server's listening port will be immediately closed and then this
     * method returns. All active HTTP exchanges will be allowed to complete
     * before the returned stage completes with {@code null}.<p>
     * 
     * If the server was just started and is still in the midst of opening the
     * server's listening port, then this method will block until the startup
     * routine is completed before initiating the shutdown.<p>
     * 
     * Upon failure to close the server's listening port, the stage will
     * complete exceptionally with an {@code IOException}.<p>
     * 
     * The returned stage can not be used to abort the shutdown.<p>
     * 
     * The returned stage represents uniquely the invocation of this method.
     * This has a few noteworthy consequences.<p>
     * 
     * 1. If the server is not {@link #isRunning() running} (listening
     * on a port) then the returned stage is already completed. This is true
     * even if exchanges from a previous run cycle is still executing (i.e. a
     * previously returned stage has yet to complete).<p>
     * 
     * 2. If the application starts the same server again concurrent to the
     * completion of the last HTTP exchange, then technically it is possible for
     * the returned stage to complete at the same time the server is considered
     * to be in a running state.<p>
     * 
     * 3. A concurrent (or subsequent) start can not hinder the returned stage
     * from completing.
     * 
     * @return the result
     */
    CompletionStage<Void> stop();
    
    /**
     * Stop listening for client connections and immediately abort all HTTP
     * exchanges.<p>
     * 
     * The server's listening port will be immediately closed and then all
     * active HTTP exchanges will be aborted. This method blocks until the job
     * is done.<p>
     * 
     * If the server was just started and is still in the midst of opening the
     * server's listening port, then this method will block until the startup
     * routine is completed before initiating the shutdown.
     * 
     * @throws IOException if an I/O error occurs
     */
    void stopNow() throws IOException;
    
    /**
     * Returns {@code true} if the server is running, otherwise {@code false}.<p>
     * 
     * By running means that the server has completed a startup, and has also
     * not completed a subsequent closure of the server's listening port. This
     * method answers the question; is the server listening on a port?<p>
     * 
     * The method does not take into account the state of lingering HTTP
     * exchanges and/or the state of underlying client channels.<p>
     * 
     * This method does not block.
     * 
     * @return {@code true} if the server is running, otherwise {@code false}
     */
    boolean isRunning();
    
    /**
     * Build a route and add it to the server.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>
     *     Route r = {@link Route}.{@link Route#builder(String)
     *               builder}(pattern).{@link Route.Builder#handler(RequestHandler, RequestHandler...)
     *               handler}(first, more).{@link Route.Builder#build()
     *               build}();
     *     return add(r);
     * </pre>
     * 
     * @param pattern of route path
     * @param first   request handler
     * @param more    optionally more request handlers
     * 
     * @return {@code this} (for chaining/fluency)
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * 
     * @throws RouteParseException
     *             if a static segment value is empty, or
     *             if parameter names are repeated in the pattern, or
     *             if a catch-all parameter is not the last segment
     * 
     * @throws HandlerCollisionException
     *             if not all handlers are unique
     * 
     * @throws RouteCollisionException
     *             if an equivalent route has already been added
     */
    default HttpServer add(String pattern, RequestHandler first, RequestHandler... more) {
        Route r = Route.builder(pattern).handler(first, more).build();
        return add(r);
    }
    
    /**
     * Add a route.
     * 
     * @param  route to add
     * @return {@code this} (for chaining/fluency)
     * 
     * @throws NullPointerException
     *             if {@code route} is {@code null}
     * 
     * @throws RouteCollisionException
     *             if an equivalent route has already been added
     * 
     * @see Route
     */
    HttpServer add(Route route);
    
    /**
     * Remove any route on the given hierarchical position.<p>
     * 
     * This method is similar to {@link #remove(Route)}, except any route no
     * matter its identity found at the hierarchical position will be removed.
     * The pattern provided is the same path-describing pattern provided to
     * methods such as {@link #add(String, RequestHandler, RequestHandler...)}
     * and {@link Route#builder(String)}, except path parameter names can be
     * anything, they simply do not matter. Other than that, the pattern will go
     * through the same normalization and validation routine.<p>
     * 
     * For example:
     * <pre>
     *   Route route = ...
     *   server.add("/download/:user/*filepath", route);
     *   server.remove("/download/:/*"); // or "/download/:bla/*bla", doesn't matter
     * </pre>
     * 
     * @param pattern of route to remove
     * 
     * @return the route removed ({@code null} if non-existent)
     * 
     * @throws IllegalArgumentException
     *             if a static segment value is empty
     * 
     * @throws IllegalStateException
     *             if a catch-all parameter is not the last segment
     */
    Route remove(String pattern);
    
    /**
     * Remove a route of a particular identity.<p>
     * 
     * The route's currently active exchanges will run to completion and will
     * not be aborted. Only when all of the exchanges have finished will the
     * route effectively not be in use anymore. However, the route is guaranteed
     * to not be <i>discoverable</i> for <i>new</i> requests once this method
     * has returned.<p>
     * 
     * In order for the route to be removed, the current route in the registry
     * occupying the same hierarchical position must be {@code equal} to the
     * given route using {@code Route.equals(Object)}. Currently, route equality
     * is not specified and the default implementation has not overridden the
     * equals method. I.e., the route provided must be the same instance.<p>
     * 
     * In order to remove <i>any</i> route at the targeted position, use {@link
     * #remove(String)} instead.
     * 
     * @param route to remove
     * 
     * @return {@code true} if successful, otherwise {@code false}
     * 
     * @throws NullPointerException if {@code route} is {@code null}
     */
    boolean remove(Route route);
    
    /**
     * Returns the server's configuration.
     *
     * @return the server's configuration (never {@code null})
     */
    Config getConfig();
    
    /**
     * Returns the socket address that the server is listening on.<p>
     * 
     * If the server was just started and is still in the midst of opening the
     * server's listening port, then this method will block until the startup
     * routine is completed before returning.
     * 
     * @return the port used by the server
     * 
     * @throws IllegalStateException if server is not running
     * @throws IOException if an I/O error occurs
     * 
     * @see AsynchronousServerSocketChannel#getLocalAddress() 
     */
    InetSocketAddress getLocalAddress() throws IllegalStateException, IOException;
}