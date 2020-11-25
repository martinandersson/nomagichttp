package alpha.nomagichttp;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.internal.DefaultServer;
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
 * Listens on a port for HTTP {@link Request requests} targeting a specific
 * {@link Route route} which contains at least one {@link RequestHandler request
 * handler} which processes the request into a {@link Response response}.<p>
 * 
 * This interface declares static <i>{@code with}</i> methods that construct
 * and return the default implementation {@link DefaultServer}. Once the server
 * has been constructed, it needs to <i>{@code start}</i>.<p>
 * 
 * Routes can be dynamically added and removed from the server using its {@link
 * #getRouteRegistry() route registry}.<p>
 * 
 * The server's function is to provide port- and channel management, parse
 * an inbound request head and resolve which handler of a route is qualified to
 * handle the request. Once the handler has been invoked, it has total freedom
 * in regards to how it interprets the request headers- and body as well as what
 * headers and body it responds.
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
 * The server instance is thread-safe and fully non-blocking once it is
 * running.<p>
 * 
 * All servers running in the same JVM share a common pool of threads (aka
 * "request threads"). The pool handles I/O completion events and executes
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
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route
 * @see RequestHandler
 */
public interface HttpServer
{
    /**
     * Builds a server using the {@linkplain ServerConfig#DEFAULT default
     * configuration}.<p>
     * 
     * @param routes of server
     * 
     * @return an instance of {@link DefaultServer}
     * 
     * @throws NullPointerException if {@code route} is {@code null}
     */
    static HttpServer with(Route... routes) {
        return with(ServerConfig.DEFAULT, List.of(routes));
    }
    
    /**
     * Builds a server.<p>
     * 
     * @param config of server
     * @param routes of server
     * 
     * @return an instance of {@link DefaultServer}
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    static HttpServer with(ServerConfig config, Route... routes) {
        return with(config, List.of(routes));
    }
    
    /**
     * Builds a server.<p>
     * 
     * @param config of server
     * @param routes of server
     * 
     * @return an instance of {@link DefaultServer}
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    static HttpServer with(ServerConfig config, Iterable<? extends Route> routes) {
        RouteRegistry reg = new DefaultRouteRegistry();
        routes.forEach(reg::add);
        return new DefaultServer(reg, config, List.of());
    }
    
    /**
     * Builds a server.<p>
     * 
     * @param config  of server
     * @param routes  of server
     * @param eh      error handler
     * 
     * @return an instance of {@link DefaultServer}
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    static HttpServer with(ServerConfig config,
                           Iterable<? extends Route> routes,
                           Supplier<? extends ErrorHandler> eh)
    {
        return with(config, routes, singleton(eh));
    }
    
    /**
     * Builds a server.<p>
     * 
     * @param config  of server
     * @param routes  of server
     * @param eh      error handlers
     * 
     * @return an instance of {@link DefaultServer}
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    static <S extends Supplier<? extends ErrorHandler>> HttpServer with(
            ServerConfig config,
            Iterable<? extends Route> routes,
            Iterable<S> eh)
    {
        RouteRegistry reg = new DefaultRouteRegistry();
        routes.forEach(reg::add);
        return new DefaultServer(reg, config, eh);
    }
    
    /**
     * Make the server listen for new client connections on a system-picked port
     * on the loopback address (IPv4 127.0.0.1, IPv6 ::1).<p>
     * 
     * This method is useful for inter-process communication on the same machine
     * or to start a server in a test environment.
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
     * @throws IllegalStateException if the server is already running
     * @throws IOException if an I/O error occurs
     * 
     * @see InetAddress
     */
    
    default HttpServer start() throws IOException {
        return start(null);
    }
    
    /**
     * Make the server listen for new client connections on the specified port
     * on the wildcard address (also known as "any local address" and "the
     * unspecified address").
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
    
    default HttpServer start(int port) throws IOException  {
        return start(new InetSocketAddress(port));
    }
    
    /**
     * Make the server listen for new client connections on the specified
     * hostname and port.
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
    default HttpServer start(String hostname, int port) throws IOException {
        return start(new InetSocketAddress(hostname, port));
    }
    
    /**
     * Make the server listen for new client connections on the specified
     * address.
     * 
     * Passing in {@code null} for address is equivalent to {@link #start()}
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
    HttpServer start(SocketAddress address) throws IOException;
    
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