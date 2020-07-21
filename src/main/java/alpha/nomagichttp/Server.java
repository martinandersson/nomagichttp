package alpha.nomagichttp;

import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.internal.AsyncServer;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.route.DefaultRouteRegistry;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteRegistry;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.NetworkChannel;
import java.util.List;

/**
 * A server receives HTTP {@link Request requests}, routes these to a {@link
 * Route route} and then calls a qualified {@link Handler handler} of that route
 * to process the request into a {@link Response response}.<p>
 * 
 * Even though a server without any routes is a legal variant, at least one must
 * be added to its {@link #getRouteRegistry() route registry} in order for the
 * server to do meaningful work.<p>
 * 
 * This interface declares static <i>{@code with}</i> methods that construct
 * and return the default implementation {@link AsyncServer}. Then, what remains
 * is to use any of the {@code start} methods to start the server on an
 * address.<p>
 * 
 * The server's function is to provide port- and channel management, parse
 * inbound request headers and possibly based on these, resolve which handler of
 * a route is qualified to handle the request. The server will not apply or
 * validate HTTP semantics once the handler has been called. The handler is in
 * complete control over how it interprets the request headers- and body as well
 * as what headers and body it responds.
 * 
 * 
 * <h3>Server Life-Cycle</h3>
 * 
 * It is possible to start many server instances on different ports. One
 * use-case for this pattern is to expose public endpoints on one port but
 * more sensitive administrator endpoints on another port.<p>
 * 
 * If at least one server is running, then the JVM will not shutdown when the
 * main application thread returns. For the application process to end, all
 * server instances must {@link #stop()}.
 * 
 * 
 * <h3>Error Handling</h3>
 * 
 * TODO: Write
 * 
 * 
 * <h3>Channel Life-Cycle</h3>
 * 
 * TODO: Write
 * 
 * 
 * <h3>Threading Model</h3>
 * 
 * The server instance is thread-safe.<p>
 * 
 * The server has a pool of threads (many times referred to as "request
 * threads"). This pool executes tasks that 1) accept new child channels on a
 * listening port (HTTP connections), 2) read/write bytes on the child channels
 * and 3) invoke the application-provided request handler.<p>
 * 
 * The server thread pool is designed for short-lived tasks and request handlers
 * should thread-off for blocking I/O operations or any other type of work that
 * is anticipated to take a long time to complete. Failure to do so could starve
 * the pool of threads able to perform other work, potentially with a negative
 * impact on scalability.<p>
 * 
 * A future API will expose the server tread pool for re-use by
 * application-provided, CPU-bound and short-lived tasks without having to
 * create yet another pool (which is a problem in all other to-date known
 * frameworks). For example, when an I/O thread returns with the result,
 * packaging the result into a client-response should be done by a request
 * thread and not the I/O thread (TODO Implement).<p>
 * 
 * In the event many servers are started on different ports, they all share the
 * same underlying thread pool.
 * 
 * 
 * <h3>Examples</h3>
 * 
 * TODO: Write
 * 
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route
 */

// TODO: The "with" methods are currently breaking with the architecture of how
//       we normally construct default implementations. Usually we do Things ->
//       ThingBuilder -> DefaultThing. I don't like having a "Servers" type, and
//       I like having "Server.with". But at least we should introduce a
//       ServerBuilder and rename AsyncServer to DefaultServer?

public interface Server
{
    /**
     * Builds a server with an initial route.<p>
     * 
     * The configuration used is {@link ServerConfig#DEFAULT} and the exception
     * handler used for all server errors and unhandled request-handler errors
     * is {@link ExceptionHandler#DEFAULT}.
     * 
     * @param  route the initial route
     * 
     * @return an instance of {@link AsyncServer}
     * 
     * @throws NullPointerException if {@code route} is {@code null}
     */
    static Server with(Route route) {
        return with(ServerConfig.DEFAULT, route);
    }
    
    /**
     * Builds a server with zero, one or many initial routes.<p>
     * 
     * The configuration used is {@link ServerConfig#DEFAULT} and the exception
     * handler used for all server errors and unhandled request-handler errors
     * is {@link ExceptionHandler#DEFAULT}.
     * 
     * @param config  server configuration
     * @param routes  initial route(s)
     * 
     * @return an instance of {@link AsyncServer}
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    static Server with(ServerConfig config, Route... routes) {
        return with(config, List.of(routes));
    }
    
    /**
     * Builds a server with zero, one or many initial routes.<p>
     * 
     * The configuration used is {@link ServerConfig#DEFAULT} and the exception
     * handler used for all server errors and unhandled request-handler errors
     * is {@link ExceptionHandler#DEFAULT}.
     * 
     * @param config  server configuration
     * @param routes  initial route(s)
     * 
     * @return an instance of {@link AsyncServer}
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    static Server with(ServerConfig config, Iterable<? extends Route> routes) {
        return with(config, routes, ExceptionHandler.DEFAULT);
    }
    
    /**
     * Builds a server with zero, one or many initial routes.
     * 
     * @param config   server configuration
     * @param routes   initial route(s)
     * @param onError  exception handler
     * 
     * @return an instance of {@link AsyncServer}
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    static Server with(ServerConfig config, Iterable<? extends Route> routes, ExceptionHandler onError) {
        RouteRegistry reg = new DefaultRouteRegistry();
        routes.forEach(reg::add);
        return new AsyncServer(reg, config, onError);
    }
    
    /**
     * Makes the server listen for new HTTP connections on a system-picked port
     * on the loopback address (IPv4 127.0.0.1, IPv6 ::1).<p>
     * 
     * This method is useful for inter-process communication or to start a
     * server in a test environment.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>{@code
     *     InetAddress addr = InetAddress.getLoopbackAddress();
     *     int port = 0;
     *     SocketAddress local = new InetSocketAddress(addr, port);
     *     return start(local);
     * }</pre>
     * 
     * @return a bound server-socket channel
     * 
     * @throws IllegalStateException if server has been started before
     * @throws IOException if an I/O error occurs
     * 
     * @see InetAddress
     */
    
    // TODO: This method should start on a hostname and port as specified in
    //       server configuration. Only if not present in the server
    //       configuration will it use a system-picked port on loopback
    //       interface. This change will also impact contract of start(null)
    //       which is specified to be equivalent to this method.
    
    // TODO: Remove IllegalStateException limitation. A server should be able to
    //       start-stop-start-stop however many times client wishes. Then update
    //       all method JavaDocs as well as the "life cycle" section on top.
    
    default NetworkChannel start() throws IOException {
        return start(null);
    }
    
    /**
     * Makes the server listen for new HTTP connections on a specified port
     * on the wildcard address ("any local address").
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>{@code
     *     return start(new InetSocketAddress(port));
     * }</pre>
     * 
     * @param port to use
     * 
     * @return a bound server-socket channel
     * 
     * @throws IllegalStateException if server has been started before
     * @throws IOException if an I/O error occurs
     * 
     * @see InetAddress
     */
    default NetworkChannel start(int port) throws IOException  {
        return start(new InetSocketAddress(port));
    }
    
    /**
     * Makes the server listen for new HTTP connections on a specified hostnamé
     * and port.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>{@code
     *     return start(new InetSocketAddress(hostname, port));
     * }</pre>
     * 
     * @param hostname to use
     * @param port to use
     * 
     * @return a bound server-socket channel
     * 
     * @throws IllegalStateException if server has been started before
     * @throws IOException if an I/O error occurs
     *
     * @see InetAddress
     */
    default NetworkChannel start(String hostname, int port) throws IOException {
        return start(new InetSocketAddress(hostname, port));
    }
    
    /**
     * Makes the server listen for new HTTP connections on a specified address.
     * 
     * Passing in {@code null} as address is equivalent to {@link #start()}
     * without any arguments, i.e. a system-picked port will be used on the
     * loopback address.
     * 
     * @param address to use
     * 
     * @return a bound server-socket channel
     *
     * @throws IllegalStateException if server has been started before
     * @throws IOException if an I/O error occurs
     *
     * @see InetAddress
     */
    NetworkChannel start(SocketAddress address) throws IOException;
    
    /**
     * TODO: Document
     * 
     * @throws UnsupportedOperationException for now
     */
    void stop();
    
    /**
     * Returns the server's route registry.
     * 
     * @return the server's route registry (never {@code null})
     */
    RouteRegistry getRouteRegistry();
    
    /**
     * Returns the server's configuration.
     *
     * @return the server's configuration (never {@code null})
     */
    ServerConfig getServerConfig();
}