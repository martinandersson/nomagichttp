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
import java.util.List;
import java.util.function.Supplier;

import static java.util.Collections.singleton;

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
 * an inbound request head and resolve which handler of a route is qualified to
 * handle the request. The server will not apply or validate HTTP semantics once
 * the handler has been called. The handler is in complete control over how it
 * interprets the request headers- and body as well as what headers and body it
 * responds.
 * 
 * 
 * <h3>Server Life-Cycle</h3>
 * 
 * It is possible to start many server instances on different ports. One
 * use-case for this pattern is to expose public endpoints on one port but
 * more sensitive administrator endpoints on another more secluded port.<p>
 * 
 * If at least one server is running, then the JVM will not shutdown when the
 * main application thread dies. For the application process to end, all
 * server instances must {@link #stop()}.
 * 
 * 
 * <h3>Threading Model</h3>
 * 
 * The server instance is thread-safe.<p>
 * 
 * The server uses only one pool of threads (many times referred to as "request
 * threads"). This pool handles I/O completion events and executes
 * application-provided entities such as the request- and error handlers. The
 * pool size is fixed and set to the value of {@link
 * ServerConfig#threadPoolSize()} at the time of the first server start.<p>
 * 
 * It is absolutely crucial that the application does not block a request
 * thread, for example by synchronously waiting on an I/O result. The request
 * thread is suitable only for short-lived and CPU-bound work. I/O work or
 * long-lived tasks should execute somewhere else. Blocking the request thread
 * will have a negative impact on scalability and could at worse starve the pool
 * of available threads making the server unable to make progress with tasks
 * such as accepting new client connections or processing other requests.<p>
 * 
 * Servers started on different ports all share the same underlying thread pool.
 * 
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route
 * @see ExceptionHandler
 * @see ServerConfig
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
        RouteRegistry reg = new DefaultRouteRegistry();
        routes.forEach(reg::add);
        return new AsyncServer(reg, config, List.of());
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
    static Server with(ServerConfig config, Iterable<? extends Route> routes, Supplier<ExceptionHandler> onError) {
        RouteRegistry reg = new DefaultRouteRegistry();
        routes.forEach(reg::add);
        return new AsyncServer(reg, config, singleton(onError));
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
     * @throws IllegalStateException if server is already running
     * @throws IOException if an I/O error occurs
     * 
     * @see InetAddress
     */
    
    // TODO: This method should start on a hostname and port as specified in
    //       server configuration. Only if not present in the server
    //       configuration will it use a system-picked port on loopback
    //       interface. This change will also impact contract of start(null)
    //       which is specified to be equivalent to this method.
    
    default Server start() throws IOException {
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
     * @return this (for chaining/fluency)
     * 
     * @throws IllegalStateException if server is already running
     * @throws IOException if an I/O error occurs
     * 
     * @see InetAddress
     */
    default Server start(int port) throws IOException  {
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
     * @return this (for chaining/fluency)
     * 
     * @throws IllegalStateException if server is already running
     * @throws IOException if an I/O error occurs
     * 
     * @see InetAddress
     */
    default Server start(String hostname, int port) throws IOException {
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
     * @return this (for chaining/fluency)
     * 
     * @throws IllegalStateException if server is already running
     * @throws IOException if an I/O error occurs
     *
     * @see InetAddress
     */
    Server start(SocketAddress address) throws IOException;
    
    /**
     * Stop the server.<p>
     * 
     * This method is NOP if server is already stopped.<p>
     * 
     * All currently running HTTP exchanges will be allowed to complete.
     * 
     * @throws IOException if an I/O error occurs
     */
    void stop() throws IOException;
    
    /**
     * Returns the port used by the server.
     * 
     * @return the port used by the server
     * 
     * @throws IllegalStateException if server is not running
     */
    int getPort() throws IllegalStateException;
    
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