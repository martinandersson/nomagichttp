package alpha.nomagichttp;

import alpha.nomagichttp.action.ActionRegistry;
import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.event.EventEmitter;
import alpha.nomagichttp.event.EventHub;
import alpha.nomagichttp.event.HttpServerStarted;
import alpha.nomagichttp.event.HttpServerStopped;
import alpha.nomagichttp.event.RequestHeadReceived;
import alpha.nomagichttp.event.ResponseSent;
import alpha.nomagichttp.handler.ExceptionHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalRequestBodyException;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteRegistry;
import alpha.nomagichttp.util.ScopedValues;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.time.Instant;
import java.util.ServiceLoader;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.IntConsumer;

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
 * interim responses, followed by a final response) is often called an
 * "exchange".<p>
 * 
 * This interface declares static <i>{@code create}</i> methods that construct
 * and return the default implementation. Once the server has been constructed,
 * it needs to <i>{@code start()}</i> which will open the server's listening
 * port.<p>
 * 
 * A trivial example:<p>
 * 
 * {@snippet :
 *   // @link substring="create" target="#create(ExceptionHandler...)" region
 *   // @link substring="add" target="#add(String, RequestHandler, RequestHandler...)" region
 *   // @link substring="GET" target="RequestHandler#GET()" region
 *   // @link substring="apply" target="RequestHandler.Builder#apply(Throwing.Function)" region
 *   // @link substring="text" target="Responses#text(String)" region
 *   // @link substring="start" target="#start(IntConsumer)" region
 *   HttpServer.create()
 *             .add("/", GET().apply(request -> text("Hello")))
 *             .start(port ->
 *                 System.out.println("Listening on port: " + port));
 *   // @end
 *   // @end
 *   // @end
 *   // @end
 *   // @end
 *   // @end
 * }
 * 
 * What was <i>added</i> to the server is a {@link RequestHandler} mapped to a
 * {@link Route} pattern. Optionally, one can also register
 * {@link BeforeAction}s that executes before the request handler (both together
 * often called the "request processing chain"), and {@link AfterAction}s that
 * can manipulate responses before they are sent. Exceptions can be handled
 * server-globally using any number of {@link ExceptionHandler}s (often called
 * the "exception processing chain").<p>
 * 
 * And there you have it, that's about all the types one needs to get familiar
 * with. No magic.
 * 
 * <h2>Server Life-Cycle</h2>
 * 
 * Almost all {@code start} methods block indefinitely, the only exception being
 * is the {@link #startAsync() startAsync} method. None of the start methods
 * create a platform thread, and all virtual threads created are always
 * non-daemon. Meaning that all opened server ports and client connections are
 * immediately closed (with no grace-period defined) when the application's main
 * thread terminates, unless the application itself created a non-daemon
 * platform thread.<p>
 * 
 * One could register an administrator endpoint to handle a graceful shut down
 * of the server, but the handler would generally be hard to implement as the
 * HTTP protocol has no built-in feature to pause and resume an exchange. What
 * to do if a client is in the middle of streaming data?<p>
 * 
 * Thus, the call to one of the blocking {@code start} methods will likely be
 * one of, if not the last, statement done by the application code; relying on
 * an external process termination to close the server.<p>
 * 
 * TODO: Give example of main method throwing what exactly?
 * <p>
 * 
 * It is possible to start multiple server instances on different ports. One
 * use-case is to expose public endpoints on one port but keep more sensitive
 * administrator endpoints on another more secluded port.<p>
 * 
 * {@snippet :
 *   try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
 *       exec.submit(() -> HttpServer.create().start(8080));
 *       exec.submit(() -> HttpServer.create().start(8081));
 *       // Implicit exec.close() will await termination of both servers
 *   }
 * }
 * 
 * This example also illustrated why all blocking {@code start} methods' return
 * type is declared {@code Void}, as opposed to {@code void}; it is to enable
 * the exception-throwing {@code start} method to be used as a
 * {@code Callable<V>} expression lambda. Otherwise, the example would not have
 * compiled (and the alternative code is very clumsy, to say the least).<p>
 * 
 * A server instance can not be recycled; it can only start and stop once.<p>
 * 
 * Code executing within the server (for example the request handler), and all
 * listeners who registered with {@link #events()}, will be able to retrieve
 * the server instance using {@link ScopedValues#httpServer()}.
 * 
 * <h2>Supported HTTP Versions</h2>
 *
 * Currently, the NoMagicHTTP library is a project in its infancy. Almost
 * complete support for HTTP/1.0 and 1.1 is the first milestone, yet to be done
 * (see POA.md in repository). HTTP/2 will be implemented thereafter. HTTP
 * clients older than HTTP/1.0 is rejected (the exchange will crash with a
 * {@link HttpVersionTooOldException}).
 * 
 * <h2>Thread Safety and Threading Model</h2>
 * 
 * All methods found on the {@code HttpServer} interface are thread-safe.<p>
 * 
 * Many components operated within the HTTP exchange is <i>not</i> thread-safe.
 * And, there's a reason for that. The default server implementation uses one
 * virtual thread per client channel, semantically known as "one thread per
 * request" (technically, one thread for each client connection, which may be
 * used for many consecutive exchanges). This thread will run through the entire
 * request processing chain and also be the one that writes the response.<p>
 * 
 * For the application developer, it is both safe and elegant to write
 * semantically blocking code. If and when a method would have blocked, the
 * virtual thread will unmount from its carrier thread, making the carrier
 * thread available to be mounted by a another virtual thread.<p>
 * 
 * The application should <i>not</i> process the request asynchronously. The
 * library API may throw a {@link WrongThreadException} if called by a platform
 * thread and that call would have caused the thread to block.<p>
 * 
 * TODO: Give bad example of concurrent outbound calls to dependent services
 * using a parallel Stream on native threads.<p>
 * 
 * TODO: Give good example using {@link StructuredTaskScope}.
 * 
 * <h2>HTTP message semantics</h2>
 * 
 * Only a very few message variants are specified to <i>not</i> have a body and
 * will be rejected by the server if they do ({@link
 * IllegalRequestBodyException}, {@link IllegalResponseBodyException}). These
 * variants <i>must</i> be rejected since including a body would likely have
 * killed the protocol.<p>
 * 
 * For all other variants of requests and responses, the body is optional and
 * the server does not reject the message based on the presence (or absence) of
 * a body. This is mostly true for all other message variants as well; the
 * server does not have an opinionated view unless an opinionated view is
 * warranted. The request handler is almost in full control over how it
 * interprets the request message and what response it returns with little
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
 * ), but it's not required to. And so the list goes on (for more info, see
 * <a href="https://stackoverflow.com/a/70157919/1268003">this StackOverflow answer</a>).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface HttpServer extends RouteRegistry, ActionRegistry
{
    /**
     * Creates a new {@code HttpServer} using {@link Config#DEFAULT}.<p>
     * 
     * The provided array of exception handlers will be copied as-is. The
     * application should make sure that the array does not contain duplicates,
     * unless for some bizarre reason it is desired to have an exception handler
     * called multiple times.
     * 
     * @param eh exception handler(s)
     * 
     * @return a new {@code HttpServer}
     * 
     * @throws NullPointerException
     *             if {@code eh} or an element therein is {@code null}
     */
    static HttpServer create(ExceptionHandler... eh) {
        return create(Config.DEFAULT, eh);
    }
    
    /**
     * Creates a new {@code HttpServer}.<p>
     * 
     * The provided array of exception handlers will be copied as-is. The
     * application should make sure that the array does not contain duplicates,
     * unless for some bizarre reason it is desired to have an exception handler
     * called multiple times for the same exception.
     * 
     * @param config of server
     * @param eh     exception handler(s)
     * 
     * @return a new {@code HttpServer}
     * 
     * @throws NullPointerException
     *             if an argument or array element is {@code null}
     */
    static HttpServer create(Config config, ExceptionHandler... eh) {
        var loader = ServiceLoader.load(HttpServerFactory.class);
        var factories = loader.stream().toList();
        if (factories.size() != 1) {
            throw new AssertionError(
                "Expected 1 factory, saw: " + factories.size());
        }
        return factories.get(0).get().create(config, eh);
    }
    
    /**
     * Listens for client connections on a system-picked port on the loopback
     * address (IPv4 127.0.0.1, IPv6 ::1).<p>
     * 
     * This method is useful for inter-process communication on the same machine
     * and for local testing. Production code should use a {@code start} method
     * that allows to specify a port or an address.<p>
     * 
     * The implementation is equivalent to:<p>
     * 
     * {@snippet :
     *   InetAddress addr = InetAddress.getLoopbackAddress();
     *   int port = 0;
     *   SocketAddress local = new InetSocketAddress(addr, port);
     *   return start(local); // @link substring="start" target="#start(SocketAddress)"
     * }
     * 
     * The given port consumer is invoked by the same thread calling this
     * method, after having bound the address but before going into the
     * accept-loop of new client connections. Thus, no code executed inside the
     * consumer should rely on the server serving requests. If this is needed,
     * use the method {@link #startAsync() startAsync} instead.
     * 
     * @param ofPort receives the listening port (must not be {@code null})
     * 
     * @return never normally
     * 
     * @throws NullPointerException
     *             if {@code ofPort} is {@code null}
     * @throws IllegalStateException
     *             if the server is already running
     * @throws IOException
     *             if an I/O error occurs
     * @throws InterruptedException
     *             if interrupted while waiting on client connections to terminate
     * 
     * @see InetAddress
     */
    Void start(IntConsumer ofPort) throws IOException, InterruptedException;
    
    /**
     * Equivalent to {@link #start(IntConsumer)}, but will not go into the
     * accept-loop.<p>
     * 
     * All other start methods block indefinitely, because the thread executing
     * one of the other start methods will bind the server's address and then
     * run the accept-loop; aka. "listen" for client connections. This method,
     * however, will bind the address, then start a virtual thread that runs the
     * accept-loop, and then return out.<p>
     * 
     * {@snippet :
     *   HttpServer testee = ...
     *   Future<Void> future = testee.startAsync();
     *   var client = new MyTestHttpClient(testee.getPort());
     *   var response = client.sendSomeRequest();
     *   assertThat(response).isEqualTo(...);
     *   testee.stop();
     *   assertThat(future)...
     * }
     * 
     * TODO: Complete the previous example
     * <p>
     * 
     * An {@code IOException} thrown by this method originates from binding the
     * address. An {@code IOException} from the accept-loop is relayed through
     * the returned {@code Future}.<p>
     * 
     * Calling the method {@code cancel} on the returned Future does nothing.
     * 
     * @return a future that completes only exceptionally
     *         (see {@link #start(SocketAddress)})
     * 
     * @throws IllegalStateException
     *             if the server is already running
     * @throws IOException
     *             if an I/O error occurs while binding the address
     */
    Future<Void> startAsync() throws IOException;
    
    /**
     * Listens for client connections on the specified port on the wildcard
     * address.<p>
     * 
     * The wildcard address is also known as "any local address" and "the
     * unspecified address".
     * 
     * @implSpec
     * The default implementation is equivalent to:<p>
     * 
     * {@snippet :
     *   // @link substring="start" target="#start(SocketAddress)" region
     *   // @link substring="InetSocketAddress" target="InetSocketAddress#InetSocketAddress(int)" region
     *   return start(new InetSocketAddress(port));
     *   // @end
     *   // @end
     * }
     * 
     * @param port to use
     * 
     * @return never normally
     * 
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
     * The default implementation is equivalent to:<p>
     * 
     * {@snippet :
     *   // @link substring="start" target="#start(SocketAddress)" region
     *   // @link substring="InetSocketAddress" target="InetSocketAddress#InetSocketAddress(String, int)" region
     *   return start(new InetSocketAddress(hostname, port));
     *   // @end
     *   // @end
     * }
     * 
     * @param hostname to use
     * @param port to use
     * 
     * @return never normally
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
     * thread is the one to bind the server's address and run an accept-loop;
     * aka. "listening" for client connections. The thread will only return once
     * the server's channel and all client connections have been closed. Then,
     * the return will only happen exceptionally, most likely with an {@link
     * AsynchronousCloseException}.<p>
     * 
     * Any other thread invoking this method whilst the server is running will
     * receive an {@link IllegalStateException}. That is to say, this method
     * never returns normally.
     * 
     * @param address to use
     * 
     * @return never normally
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
     * This method will also close all client connections that are not being
     * used, and signal active HTTP exchanges to close the client connection
     * after the final response.<p>
     * 
     * The thread invoking this method and the thread blocked in
     * {@code start()}, will only return when the last client connection is
     * closed. Which thread return first is not defined.<p>
     * 
     * Using this method can, in theory, result in both threads waiting forever
     * for the completion of HTTP exchanges (the server guards against stale
     * exchanges not making any progress but does not impose a maximum runtime
     * length). Consider using one of the other {@code stop} methods.<p>
     * 
     * This method is NOP if the server is stopping or has already stopped.
     * 
     * @throws IOException
     *             if an I/O error occurs
     * @throws InterruptedException
     *             if interrupted while waiting on client connections to terminate
     */
    void stop() throws IOException, InterruptedException;
    
    /**
     * Closes the port listening for client connections.<p>
     * 
     * Equivalent to {@link #stop()}, except the graceful period — during which
     * the threads await an orderly close of all client connections — ends after
     * the given timeout, at which point all client connections will close.<p>
     * 
     * This method has no effect if the server has already stopped. If the
     * server is in the process of stopping, then this method could, in theory,
     * overwrite a previously set timeout.
     * 
     * @param timeout when to force-close all client connections
     * 
     * @throws NullPointerException
     *             if {@code timeout} is {@code null}
     * @throws IOException
     *             if an I/O error occurs
     * @throws InterruptedException
     *             if interrupted while waiting on client connections to terminate
     */
    void stop(Duration timeout) throws IOException, InterruptedException;
    
    /**
     * Closes the port listening for client connections.<p>
     * 
     * Equivalent to {@link #stop(Duration)}, except the end of the graceful
     * period is specified using an {@link Instant}.
     * 
     * @param deadline when to force-close all client connections
     * 
     * @throws NullPointerException
     *             if {@code deadline} is {@code null}
     * @throws IOException
     *             if an I/O error occurs
     * @throws InterruptedException
     *             if interrupted while waiting on client connections to terminate
     */
    void stop(Instant deadline) throws IOException, InterruptedException;
    
    /**
     * Closes the port listening for client connections.<p>
     * 
     * This method will also close all client connections, regardless if the
     * connection is being used by an active HTTP exchange.<p>
     * 
     * This method has no effect if the server has already stopped. If the
     * server is in the process of stopping, then this method will void a
     * previously set deadline for a graceful period.<p>
     * 
     * As is the case with all {@code stop} methods; this method will only
     * return once all client connections have closed.
     * 
     * @throws IOException
     *             if an I/O error occurs
     * @throws InterruptedException
     *             if interrupted while waiting on client connections to terminate
     */
    void kill() throws IOException, InterruptedException;
    
    /**
     * {@return {@code true} if the server is running, otherwise
     * {@code false}}<p>
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
     */
    boolean isRunning();
    
    /**
     * {@return the event hub associated with this server}<p>
     * 
     * The event hub can be used to subscribe to server-related events. For
     * example:<p>
     * 
     * {@snippet :
     *   HttpServer server = ...
     *   server.events().on(HttpServerStarted.class,
     *           (event, when) -> System.out.println("Server started at " + when));
     * }
     * 
     * The hub can also be used to emit application-specific events
     * programmatically, even from application-code running within the
     * server.<p>
     * 
     * {@snippet :
     *   // @link substring="httpServer" target="ScopedValues#httpServer()" region
     *   // @link substring="dispatch" target="EventHub#dispatch(Object)" region
     *   httpServer().events().dispatch("Hello!");
     *   // @end
     *   // @end
     * }
     * 
     * The hub is not bound to the running state of the server. The hub can be
     * used by the application to dispatch its own events before the server has
     * started, while the server is running and after the server has stopped.<p>
     * 
     * If the application runs multiple servers, a JVM-global hub can be created
     * like so:<p>
     * 
     * {@snippet :
     *   EventHub one = server1.events(),
     *            two = server2.events(),
     *            // @link substring="combine" target="EventHub#combine(ScatteringEventEmitter, ScatteringEventEmitter, ScatteringEventEmitter...)" :
     *            all = EventHub.combine(one, two);
     * }
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
     * @see EventEmitter
     */
    EventHub events();
    
    /**
     * {@return the server's configuration}
     */
    Config getConfig();
    
    /**
     * {@return the socket address this server's channel's socket is bound to}
     * 
     * @throws IllegalStateException
     *             if the server is not running
     * @throws IOException
     *             if an I/O error occurs
     * 
     * @see ServerSocketChannel#getLocalAddress()
     */
    SocketAddress getLocalAddress() throws IOException;
    
    /**
     * {@return the port this server is listening to}
     * 
     * @implSpec
     * The default implementation is equivalent to:<p>
     * 
     * {@snippet :
     *   // @link substring="InetSocketAddress" target="InetSocketAddress" region
     *   // @link substring="getLocalAddress" target="#getLocalAddress()" region
     *   // @link substring="getPort" target="InetSocketAddress#getPort()" region
     *   return ((InetSocketAddress) getLocalAddress()).getPort();
     *   // @end
     *   // @end
     *   // @end
     * }
     * 
     * @throws IllegalStateException
     *             if the server is not running
     * @throws ClassCastException
     *             if the server was created using an
     *             {@link UnixDomainSocketAddress}
     * @throws IOException
     *             if an I/O error occurs
     */
    default int getPort() throws IOException {
        return ((InetSocketAddress) getLocalAddress()).getPort();
    }
}
