package alpha.nomagichttp;

import alpha.nomagichttp.action.ActionRegistry;
import alpha.nomagichttp.event.EventEmitter;
import alpha.nomagichttp.event.EventHub;
import alpha.nomagichttp.event.HttpServerStarted;
import alpha.nomagichttp.event.HttpServerStopped;
import alpha.nomagichttp.event.RequestHeadReceived;
import alpha.nomagichttp.event.ResponseSent;
import alpha.nomagichttp.event.ScatteringEventEmitter;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.internal.DefaultServer;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalRequestBodyException;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteRegistry;
import jdk.incubator.concurrent.StructuredTaskScope;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.time.Instant;

import static java.net.InetAddress.getLoopbackAddress;

/**
 * Listens on a port for HTTP connections.<p>
 * 
 * The server's function is to provide port- and channel management, parse
 * an inbound request head and resolve a handler against a target route (also
 * known as a "server resource") that is qualified to handle the request. The
 * handler has almost total freedom in regards to how it interprets the request
 * headers- and body as well as what headers and body it responds.<p>
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
 * 
 * <h2>Server Life-Cycle</h2>
 * 
 * It is possible to start server instances on different ports. One use-case for
 * this pattern is to expose public endpoints on one port but keep more
 * sensitive administrator endpoints on another more secluded port.<p>
 * 
 * TODO: Give StructuredTaskScope example.<p>
 * 
 * A server instance can not be recycled; it can only start and stop once.<p>
 * 
 * 
 * <h2>Supported HTTP Versions</h2>
 *
 * Currently, the NoMagicHTTP server is a project in its infancy. Almost
 * complete support for HTTP/1.0 and 1.1 is the first milestone, yet to be done
 * (see POA.md in repository). HTTP/2 will be implemented thereafter. HTTP
 * clients older than HTTP/1.0 is rejected (the exchange will crash with a
 * {@link HttpVersionTooOldException}).<p>
 * 
 * 
 * <h2>Thread Safety and Threading Model</h2>
 * 
 * All methods found on the {@code HttpServer} interface are thread-safe.<p>
 * 
 * The default implementation uses virtual threads to handle inbound
 * connections, invoke the application's request handlers and so on. Using
 * virtual threads enables the rest of the library API to expose a simple and
 * semantically blocking API to the application code, but virtual threads do not
 * block; instead they will unmount from the carrier thread making it available
 * to be mounted by a different virtual thread.<p>
 * 
 * It is safe to call methods found in the {@code HttpServer} interface, its
 * super-interfaces and related API (e.g. constructing objects such as routes
 * and handlers) from a native thread.<p>
 * 
 * However, application code executing within the scope of an HTTP exchange
 * should not use other parts of the library API from a native thread. Parts of
 * the API that would've caused a native thread to block reserves the right to
 * throw a {@link WrongThreadException}, if called using a native thread.<p>
 * 
 * TODO: Give bad example of concurrent outbound calls to dependent services
 * using a parallel Stream on native threads.<p>
 * 
 * TODO: Give good example using {@link StructuredTaskScope}<p>
 * 
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
 * the server does not reject the message based on the presence (or absence) of
 * a body. This is mostly true for all other message variants as well; the
 * server does not have an opinionated view unless an opinionated view is
 * warranted. The request handler is almost in full control over how it
 * interprets the request message and what response it returns with no
 * interference from the server.<p>
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
     * Creates a server.<p>
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
     *             if an argument or array element is {@code null}
     */
    static HttpServer create(Config config, ErrorHandler... eh) {
        return new DefaultServer(config, eh);
    }
    
    /**
     * Listens for client connections on a system-picked port on the loopback
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
     * @return nothing
     * 
     * @throws NullPointerException
     *             if {@code address} is {@code null}
     * @throws IllegalStateException
     *             if server is already running
     * @throws IOException
     *             if an I/O error occurs
     * @throws InterruptedException
     *             if interrupted while waiting on client connections to terminate
     * 
     * @see InetAddress
     */
    default Void start() throws IOException, InterruptedException {
        return start(new InetSocketAddress(getLoopbackAddress(), 0));
    }
    
    /**
     * Listens for client connections on the specified port on the wildcard
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
     * @return nothing
     * 
     * @throws NullPointerException
     *             if {@code address} is {@code null}
     * @throws IllegalStateException
     *             if the server is already running
     * @throws IOException
     *             if an I/O error occurs
     * @throws InterruptedException
     *             if interrupted while waiting on client connections to terminate
     * 
     * @see InetAddress
     * @see #start(SocketAddress)
     */
    default Void start(int port) throws IOException, InterruptedException {
        return start(new InetSocketAddress(port));
    }
    
    /**
     * Listens for client connections on a given hostname and port.
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
     * @return nothing
     * 
     * @throws NullPointerException
     *             if {@code hostname} is {@code null}
     * @throws IllegalStateException
     *             if the server is already running
     * @throws IOException
     *             if an I/O error occurs
     * @throws InterruptedException
     *             if interrupted while waiting on client connections to terminate
     * 
     * @see InetAddress
     * @see #start(SocketAddress) 
     */
    default Void start(String hostname, int port) throws IOException, InterruptedException {
        return start(new InetSocketAddress(hostname, port));
    }
    
    /**
     * Listens for client connections.<p>
     * 
     * This method may block for an unlimited period of time if the calling
     * thread is the one to bind the server's address and go into a listening
     * accept-loop. The thread will only return once the server's channel and
     * all client connections have been closed. Then, the return will only
     * happen exceptionally, likely with an {@link
     * AsynchronousCloseException}.<p>
     * 
     * Any other thread invoking this method whilst the server is running will
     * receive an {@link IllegalStateException}. That is to say, this method
     * never returns normally.<p>
     * 
     * The return type is declared {@code Void} as opposed to {@code void} to
     * enable this method to be used as a {@code Callable<V>} expression lambda.
     * 
     * @param address to use
     * 
     * @return nothing
     * 
     * @throws NullPointerException
     *             if {@code address} is {@code null}
     * @throws IllegalStateException
     *             if the server is already running
     * @throws IOException
     *             if an I/O error occurs
     * @throws InterruptedException
     *             if interrupted while waiting on client connections to terminate
     * 
     * @see InetAddress
     */
    Void start(SocketAddress address) throws IOException, InterruptedException;
    
    /**
     * Closes the port listening for client connections.<p>
     * 
     * This method will cause all client connections that are currently not
     * being used to close and signal all active HTTP exchanges to close the
     * underlying client connection after the final response. This is also known
     * as a graceful shutdown.<p>
     * 
     * The thread blocked in {@code start()} will only return when the last
     * client connection is closed. The thread invoking this method returns
     * immediately.<p>
     * 
     * Using this method can in theory result in the {@code start} thread
     * waiting forever for the completion of HTTP exchanges (the server guards
     * against stale exchanges not making any progress but does not impose a
     * maximum runtime length). Consider using one of the other {@code stop}
     * methods.<p>
     * 
     * This method is NOP if the server has already stopped.
     * 
     * @throws IOException if an I/O error occurs
     */
    void stop() throws IOException;
    
    /**
     * Closes the port listening for client connections.<p>
     * 
     * Equivalent to {@link #stop()}, except the graceful period — during which
     * the thread in {@code start()} awaits an orderly close of all client
     * connections — ends around the time of the deadline, at which point
     * all client connections will close.<p>
     * 
     * Technically, this method is never NOP, because it will always set a new
     * deadline (and overwrite a previously set deadline/timeout). However, this
     * in turn will only have a noticeable effect if the server hasn't already
     * stopped.
     * 
     * @param deadline when to force-close all client connections
     * 
     * @throws NullPointerException
     *             if {@code deadline} is {@code null}
     * @throws IOException
     *             if an I/O error occurs
     */
    void stop(Instant deadline) throws IOException;
    
    /**
     * Closes the port listening for client connections.<p>
     * 
     * Equivalent to {@link #stop()}, except the graceful period — during which
     * the thread in {@code start()} awaits an orderly close of all client
     * connections — ends after a given timeout, at which point all client
     * connections will close.<p>
     * 
     * Technically, this method is never NOP, because it will always set a new
     * deadline (and overwrite a previously set deadline/timeout). However, this
     * in turn will only have a noticeable effect if the server hasn't already
     * stopped.
     * 
     * @param timeout when to force-close all client connections
     * 
     * @throws NullPointerException
     *             if {@code timeout} is {@code null}
     * @throws IOException
     *             if an I/O error occurs
     */
    void stop(Duration timeout) throws IOException;
    
    /**
     * Closes the port listening for client connections.<p>
     * 
     * This method will cause all client connections to close, regardless if
     * the connection is currently being used by an active HTTP exchange.<p>
     * 
     * If the server has not started, or it has already stopped, then this
     * method is NOP.
     * 
     * @throws IOException if an I/O error occurs
     */
    void kill() throws IOException;
    
    /**
     * Returns {@code true} if the server is running.<p>
     * 
     * By running means, is the server listening on a port for new client
     * connections?<p>
     * 
     * This method does not take into account the state of client connections.
     * Only a return from the thread waiting in {@code start()} indicates the
     * end of all client connections.<p>
     * 
     * The answer is an approximated assumption; this method does not probe the
     * server channel's actual state.<p>
     * 
     * A {@code true} return value is always correct. This method will never
     * return {@code true} before the server's channel has been bound and the
     * method will never return {@code true} after the channel has been
     * closed.<p>
     * 
     * This method could return a false {@code false} only during a minuscule
     * time window when another thread is executing {@code stop/kill} and was
     * scheduled to close the channel. A {@code false} return value will
     * therefor semantically mean "server is not running, or it is just about to
     * stop within the blink of an eye".
     * 
     * @return {@code true} if the server is running
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
     *     <td> {@link RawRequest.Head} </td>
     *     <td> {@link RequestHeadReceived.Stats} </td>
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
     * 
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
     * Returns the socket address that the server's channel's socket is bound to.
     * 
     * @return the socket address that the server's channel's socket is bound to
     *         (never {@code null})
     * 
     * @throws IllegalStateException if the server is not running
     * @see ServerSocketChannel#getLocalAddress()
     */
    SocketAddress getLocalAddress() throws IOException;
}