package alpha.nomagichttp;

import alpha.nomagichttp.action.ActionRegistry;
import alpha.nomagichttp.event.EventEmitter;
import alpha.nomagichttp.event.EventHub;
import alpha.nomagichttp.event.HttpServerStarted;
import alpha.nomagichttp.event.HttpServerStopped;
import alpha.nomagichttp.event.RequestHeadReceived;
import alpha.nomagichttp.event.ResponseSent;
import alpha.nomagichttp.event.ScatteringEventEmitter;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.internal.DefaultServer;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalRequestBodyException;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.RequestHead;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteRegistry;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

import static java.net.InetAddress.getLoopbackAddress;

/**
 * Listens on a port for HTTP connections.<p>
 * 
 * The server's function is to provide port- and channel management, parse
 * an inbound request head and resolve a handler within a target route (also
 * known as a "server resource") that is qualified to handle the request. Once
 * the handler has been invoked, it has almost total freedom in regards to how
 * it interprets the request headers- and body as well as what headers and body
 * it responds.<p>
 * 
 * The process of receiving a request and respond responses (any number of
 * intermittent responses, followed by a final response) is often called an
 * "exchange".<p>
 * 
 * This interface declares static <i>{@code create}</i> methods that construct
 * and return the default implementation {@link DefaultServer}. Once the server
 * has been constructed, it needs to <i>{@code start()}</i> which will open the
 * server's listening port.<p>
 * 
 * Routes can be dynamically added and removed using {@link #add(Route)
 * add(Route)} and {@link #remove(Route) remove(Route)}. A legal server variant
 * is to not even have any routes registered. The idea is that resources (what's
 * "behind the route") can be short-lived and serve very specific purposes, so
 * their presence can change.<p>
 * 
 * Example:
 * <pre>
 *   HttpServer.{@link #create(ErrorHandler...)
 *     create}().{@link #add(String, RequestHandler, RequestHandler...)
 *       add}("/", {@link RequestHandler#GET()
 *         GET}().{@link RequestHandler.Builder#respond(Response)
 *           respond}({@link Responses#text(String)
 *             text}("Hello"))).{@link #start() start}();
 * </pre>
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
 * {@link HttpVersionTooOldException}).
 * 
 * <h2>HTTP message semantics</h2>
 * 
 * Only a very few message variants are specified to <i>not</i> have a body and
 * will be rejected by the server if they do ({@link
 * IllegalRequestBodyException}, {@link IllegalResponseBodyException}). These
 * variants <i>must</i> be rejected since including a body would have likely
 * killed the protocol.<p>
 * 
 * For all other variants of requests and responses, the body is optional and
 * the server does not reject the message based on the presence of a body. This
 * is mostly true for all other message variants as well; the server does not
 * have an opinionated view unless an opinionated view is warranted. The request
 * handler is mostly in control over how it interprets the request message and
 * what response it returns with no interference.<p>
 * 
 * For example, it might not be common but it <i>is</i>
 * allowed for {@link HttpConstants.Method#GET GET} requests (
 * <a href="https://tools.ietf.org/html/rfc7231#section-4.3.1">RFC 7231 §4.3.1</a>
 * ) to have a body and for {@link HttpConstants.Method#POST POST} requests to
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
 *   RequestHandler h = GET().{@link RequestHandler.Builder#accept(RequestHandler.Logic)
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
 *   RequestHandler h = GET().accept((request, channel) -{@literal >} {
 *       CompletionStage{@literal <}String{@literal >} data = database.fetchAsync("SELECT * FROM Something");
 *       CompletionStage{@literal <}Response{@literal >} resp = data.thenApply(Responses::text);
 *       channel.write(resp);
 *   });
 * </pre>
 * 
 * The problem is <i>not</i> synchronously producing a response <i>if</i> one
 * can be produced without blocking.
 * <pre>
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
public interface HttpServer extends RouteRegistry, ActionRegistry
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
     * called multiple times for the same error.
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
        return new DefaultServer(config, eh);
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
     * @throws IllegalStateException
     *             if the server is already running (see {@link #start(SocketAddress)})
     * @throws IOException
     *             if an I/O error occurs
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
     * @throws IllegalStateException
     *             if the server is already running (see {@link #start(SocketAddress)})
     * @throws IOException
     *             if an I/O error occurs
     * 
     * @see InetAddress
     * @see #start(SocketAddress)
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
     * @throws IllegalStateException
     *             if the server is already running (see {@link #start(SocketAddress)})
     * @throws IOException
     *             if an I/O error occurs
     * 
     * @see InetAddress
     * @see #start(SocketAddress) 
     */
    default HttpServer start(String hostname, int port) throws IOException {
        return start(new InetSocketAddress(hostname, port));
    }
    
    /**
     * Listen for client connections on a given address.<p>
     * 
     * This method blocks if the invoking thread is the one to initiate the
     * startup routine. Another thread invoking this method concurrently will
     * receive an {@link IllegalStateException}.
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
     * method returns. No HTTP exchanges are aborted, but no new exchanges will
     * begin. When all client connections that were accepted through the port
     * have been closed the return stage completes.<p>
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
     * Stop listening for client connections and immediately close all active
     * client connections (no HTTP exchanges will be able to progress
     * further).<p>
     * 
     * This method blocks until the listening port and all connections have been
     * closed.<p>
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
     * Returns the event hub associated with this server.<p>
     * 
     * The event hub can be used to subscribe to server-related events. For
     * example:
     * <pre>{@code
     *   HttpServer server = ...
     *   server.events().on(HttpServerStarted.class, (event, when) ->
     *           System.out.println("Server started at " + when));
     * }</pre>
     * 
     * The hub can also be used to emit application-specific events
     * programmatically.
     * <pre>{@code
     *   server.events().on(String.class, msg ->
     *           System.out.println("Received message: " + msg));
     *   server.events().dispatch("Hello!");
     * }</pre>
     * 
     * The hub is not bound to the running state of the server. The hub can be
     * used by the application to dispatch its own events before the server has
     * started, while the server is running and after the server has stopped.<p>
     * 
     * If the application runs multiple servers, a JVM-global hub can be created
     * like so:
     * <pre>
     *   EventHub one = server1.events(),
     *            two = server2.events(),
     *            all = EventHub.{@link
     *   EventHub#combine(ScatteringEventEmitter, ScatteringEventEmitter, ScatteringEventEmitter...) combine}(one, two);
     * </pre>
     * 
     * All event objects emitted by the HttpServer is an enum instance and does
     * not contain any event-specific information. The event metadata is passed
     * as attachments. The following table lists the events emitted by the
     * HttpServer.
     * 
     * <table class="striped">
     *   <caption style="display:none">Events emitted</caption>
     *   <thead>
     *   <tr>
     *     <th scope="col">Type</th>
     *     <th scope="col">Attachment 1</th>
     *     <th scope="col">Attachment 2</th>
     *   </tr>
     *   </thead>
     *   <tbody>
     *   <tr>
     *     <th scope="row"> {@link HttpServerStarted} </th>
     *     <td> {@link Instant} </td>
     *     <td> {@code null} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link HttpServerStopped} </th>
     *     <td> {@link Instant} </td>
     *     <td> {@link Instant} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link RequestHeadReceived} </th>
     *     <td> {@link RequestHead} </td>
     *     <td> {@code null} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link ResponseSent} </th>
     *     <td> {@link Response} </td>
     *     <td> {@link ResponseSent.Stats} </td>
     *   </tr>
     *   </tbody>
     * </table>
     * 
     * @return the event hub associated with this server (never {@code null})
     * @see EventEmitter
     */
    EventHub events();
    
    /**
     * Returns the server's configuration.
     * 
     * @return the server's configuration (never {@code null})
     */
    Config getConfig();
    
    /**
     * Returns the socket address that the server is listening on.
     * 
     * @return the port used by the server
     * 
     * @throws IllegalStateException if the server is not running
     * @throws IOException if an I/O error occurs
     * 
     * @see AsynchronousServerSocketChannel#getLocalAddress() 
     */
    InetSocketAddress getLocalAddress() throws IllegalStateException, IOException;
}