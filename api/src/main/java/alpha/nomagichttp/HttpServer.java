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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.time.Instant;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.IntConsumer;

/// Listens on a port for HTTP connections.
/// 
/// The server's function is to provide port- and channel management, parse
/// a request head and resolve a handler based on the targeted resource.
/// 
/// A trivial example:
/// {@snippet :
///    // @link substring="create" target="#create(ExceptionHandler...)" region
///    // @link substring="add" target="#add(String, RequestHandler, RequestHandler...)" region
///    // @link substring="GET" target="RequestHandler#GET()" region
///    // @link substring="apply" target="RequestHandler.Builder#apply(Throwing.Function)" region
///    // @link substring="text" target="alpha.nomagichttp.message.Responses#text(String)" region
///    // @link substring="start" target="#start(IntConsumer)" region
///    HttpServer.create()
///              .add("/", GET().apply(request -> text("Hello")))
///              .start(port -> System.out.println("Listening on " + port));
///    // @end
///    // @end
///    // @end
///    // @end
///    // @end
///    // @end
///  }
/// 
/// What was added to the server is a [Route] pattern (server root "/") and
/// a [RequestHandler] which responds "Hello" to a request specifying the "GET"
/// method.
/// 
/// To implement cross-cutting concerns, one can preempt request handlers
/// with a [BeforeAction] and transform responses with an [AfterAction]. For
/// example, to authenticate a user and to propagate a correlation ID,
/// respectively.
/// 
/// Exceptions can be handled on a server-global level using [ExceptionHandler].
/// Another use case would be to modify or replace the server's default error
/// responses.
/// 
/// The [EventHub] returned from [#events()] can be used to observe events from
/// the server, but also to dispatch and observe application-defined events.
/// 
/// 
/// # Life cycle
/// 
/// A server instance can not be recycled; it can start and stop only once.
/// 
/// The server is blazingly fast to create, start and stop. The developer is
/// encouraged to run a real server (no mock) in test cases.
/// 
/// ### Start
/// 
/// All `start(...)` methods block indefinitely.
/// 
/// One can start the server asynchronously using [#startAsync()]. This is
/// useful if the application's main thread needs to do post-start work. For
/// example, test code exercising and asserting an HTTP exchange.
/// 
/// It is possible to start multiple server instances on different ports.
/// 
/// One use-case could be to expose public endpoints on one port but to keep
/// more sensitive administrator endpoints on another, secluded port:
/// 
/// {@snippet :
///    // @link substring="newVirtualThreadPerTaskExecutor()" target="java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor" region
///    // @link substring="close()" target="java.util.concurrent.ExecutorService#close" region
///    try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
///        exec.submit(() -> HttpServer.create().start(8080));
///        exec.submit(() -> HttpServer.create().start(8081));
///    } // Implicit call to ExecutorService.close() will await termination of both servers
///    // @end
///    // @end
///  }
/// 
/// 
/// ### Stop
/// 
/// If a `stop(...)` method is called, then depending on how the server was
/// started, either a thread blocked in a `start(...)` method returns
/// exceptionally with a [ClosedChannelException], or the returned `Future` from
/// `startAsync()` completes exceptionally with an [ExecutionException] caused
/// by a `ClosedChannelException`.
/// 
/// In other words, the `start(...)` methods and the `Future` never
/// returns/completes normally. Calling `start(...)` will likely be the last
/// statement done by the application code; relying on an external process
/// termination to close the server.
/// 
/// The server never creates a non-daemon platform thread (virtual threads are
/// always daemon).
/// 
/// This means that all opened ports and client connections are immediately
/// closed (without a graceful period) when the application's main thread
/// terminates, unless the application itself started a still-running,
/// non-daemon thread.
/// 
/// Keep that in mind when using the `startAsync()` method!
/// 
/// 
/// # Scoped values
/// 
/// Code executing within the server is able to retrieve the running server
/// instance using [ScopedValues#httpServer()]. Such entities are before- and
/// after-actions, request- and exception handlers, and event listeners.
/// 
/// 
/// # Threading Model
/// 
/// The server implementation starts and dedicates one virtual thread per client
/// connection. In any given moment, there will be as many virtual threads as
/// there are connections — plus one if the server was started using
/// `startAsync()`.
/// 
/// The per-connection virtual thread is commonly referred to as a "request
/// thread", because what it does is to repeatedly (while the connection lasts):
/// read data, execute the request processing chain and write a response.
/// 
/// For the application developer, it is both safe and elegant to write
/// semantically blocking code.
/// 
/// Quoting [JEP-444](https://openjdk.org/jeps/444):
/// 
/// > _The vast majority of blocking operations in the JDK will unmount the
/// > virtual thread, freeing its carrier and the underlying OS thread to take
/// > on new work._
/// 
/// Should the need arise to perform asynchronous work, start a new virtual
/// thread.
/// 
/// The NoMagicHTTP API may throw a [WrongThreadException] if called by a
/// platform thread and that call would have caused the thread to block.
/// 
/// TODO: Give bad example of concurrent outbound calls to dependent services
/// using a parallel Stream on native threads.
/// 
/// TODO: Give good example using [StructuredTaskScope].
/// 
/// 
/// # HTTP message semantics
/// 
/// Very few message variants are specified to _not_ have a body and will be
/// rejected by the server if they do (see [IllegalRequestBodyException] and
/// [IllegalResponseBodyException]).
/// 
/// These variants must be rejected because including a body would likely have
/// killed the protocol. For all other variants, the body is optional and the
/// server does not reject the message based on the presence (or absence) of a
/// body.
/// 
/// This is mostly true for all other message variants as well; the server does
/// not have an opinionated view unless warranted. The request handler is almost
/// in full control over how it interprets the request and what response it
/// returns.
/// 
/// For example, it might not be common but it is
/// allowed for [GET][HttpConstants.Method#GET] requests (
/// [RFC 7231 §4.3.1](https://tools.ietf.org/html/rfc7231#section-4.3.1)) to
/// have a body and for [POST][HttpConstants.Method#POST] requests to not have a
/// body ([RFC 7230 §3.3.2](https://tools.ietf.org/html/rfc7230#section-3.3.2)).
/// 
/// Similarly, the [201 (Created)][HttpConstants.StatusCode#TWO_HUNDRED_ONE]
/// response often have a body which "typically describes and links to the
/// resource(s) created" (
/// [RFC 7231 §6.3.2](https://tools.ietf.org/html/rfc7231#section-6.3.2)), but
/// it is not required to.
/// 
/// And so the list goes on. For more information, see
/// [this StackOverflow answer](https://stackoverflow.com/a/70157919/1268003)).
/// 
/// 
/// # Supported HTTP Versions
/// 
/// Currently, NoMagicHTTP is an immature project.
/// 
/// Almost complete support for HTTP/1.0 and 1.1 is the first milestone, yet to
/// be done (see POA.md in the code repository).
/// 
/// HTTP/2 and/or HTTP/3 will be implemented thereafter.
/// 
/// For more details, see [Config#minHttpVersion()].
/// 
/// > _Fast is fine, but accuracy is final._ — Wyatt Earp
/// 
/// @apiNote
/// The provided example using an `ExecutorService` illustrates why all
/// `start(...)` methods return [Void] (as opposed to `void`); to enable the
/// exception-throwing `start(...)` methods to be used as a [Callable]`<V>`
/// expression lambda.
/// 
/// @implSpec
/// The implementation is thread-safe.
/// 
/// The implementation inherits the identity-based implementations of
/// [Object#hashCode()] and [Object#equals(Object)].
/// 
/// @author Martin Andersson (webmaster at martinandersson.com)
public interface HttpServer extends RouteRegistry, ActionRegistry
{
    /// Creates a new `HttpServer` using [Config#DEFAULT].
    /// 
    /// The given array of exception handlers will be copied as-is.
    /// 
    /// There is no argument validation preventing duplicates.
    /// 
    /// @param exceptionHandlers zero or more exception handlers
    /// 
    /// @return a new `HttpServer`
    /// 
    /// @throws NullPointerException
    ///             if `exceptionHandlers` or an element therein is `null`
    static HttpServer create(ExceptionHandler... exceptionHandlers) {
        return create(Config.DEFAULT, exceptionHandlers);
    }
    
    /// Creates a new `HttpServer`.
    /// 
    /// The given array of exception handlers will be copied as-is.
    /// 
    /// There is no argument validation preventing duplicates.
    /// 
    /// @param config server configuration
    /// @param exceptionHandlers zero or more exception handlers
    /// 
    /// @return a new `HttpServer`
    /// 
    /// @throws NullPointerException
    ///             if an argument or array element is `null`
    static HttpServer create(Config config, ExceptionHandler... exceptionHandlers) {
        var loader = ServiceLoader.load(HttpServerFactory.class);
        var factories = loader.stream().toList();
        if (factories.size() != 1) {
            throw new AssertionError(
                "Expected 1 factory, saw: " + factories.size());
        }
        return factories.get(0).get().create(config, exceptionHandlers);
    }
    
    /// Listens for client connections on a system-picked port on the loopback
    /// address (IPv4 127.0.0.1, IPv6 ::1).
    /// 
    /// The given port consumer is invoked by the same thread calling this
    /// method, after having bound the address but before going into the
    /// accept-loop (listen for client connections).
    /// 
    /// Therefore, no code executing inside the consumer should rely on the
    /// server's ability to serve requests. If this is needed, use the method
    /// [startAsync][#startAsync()] instead.
    /// 
    /// The implementation is equivalent to:
    /// {@snippet :
    ///    // @link substring="getLoopbackAddress" target="InetAddress#getLoopbackAddress" region
    ///    // @link substring="InetSocketAddress" target="InetSocketAddress#InetSocketAddress(InetAddress, int)" region
    ///    // @link substring="start" target="#start(SocketAddress)" region
    ///    InetAddress address = InetAddress.getLoopbackAddress();
    ///    int port = 0;
    ///    SocketAddress local = new InetSocketAddress(address, port);
    ///    return start(local);
    ///    // @end
    ///    // @end
    ///    // @end
    ///  }
    /// 
    /// @param ofPort receives the listening port
    /// 
    /// @return never normally
    /// 
    /// @throws NullPointerException
    ///             if `ofPort` is `null`
    /// @throws IllegalStateException
    ///             if the server is already running
    /// @throws IOException
    ///             if an I/O error occurs
    /// @throws InterruptedException
    ///             if interrupted while waiting on client connections to terminate
    /// 
    /// @see InetAddress
    Void start(IntConsumer ofPort) throws IOException, InterruptedException;
    
    /// Listens for client connections on a system-picked port on the loopback
    /// address (IPv4 127.0.0.1, IPv6 ::1).
    /// 
    /// A virtual thread will be started to run the accept-loop and then this
    /// method returns normally.
    /// 
    /// An [IOException] thrown by this method originates from binding the
    /// address. An `IOException` from the accept-loop is relayed through
    /// the returned `Future`.
    /// 
    /// Calling [cancel][Future#cancel(boolean)] on the returned `Future` does
    /// nothing.
    /// 
    /// @return a future that completes exceptionally
    /// 
    /// @throws IllegalStateException
    ///             if the server is already running
    /// @throws IOException
    ///             if an I/O error occurs while binding the address
    /// 
    /// @see HttpServer
    Future<Void> startAsync() throws IOException;
    
    /// Listens for client connections on the specified port on the wildcard
    /// address.
    /// 
    /// The wildcard address is also known as "any local address" and "the
    /// unspecified address".
    /// 
    /// @implSpec
    /// The default implementation is:
    /// 
    /// {@snippet :
    ///    // @link substring="start" target="#start(SocketAddress)" region
    ///    // @link substring="InetSocketAddress" target="InetSocketAddress#InetSocketAddress(int)" region
    ///    return start(new InetSocketAddress(port));
    ///    // @end
    ///    // @end
    ///  }
    /// 
    /// @param port the port number
    /// 
    /// @return never normally
    /// 
    /// @throws IllegalStateException
    ///             if the server is already running
    /// @throws IOException
    ///             if an I/O error occurs
    /// @throws InterruptedException
    ///             if interrupted while waiting on client connections to terminate
    /// 
    /// @see InetAddress
    default Void start(int port) throws IOException, InterruptedException {
        return start(new InetSocketAddress(port));
    }
    
    /// Listens for client connections on the specified hostname and port.
    /// 
    /// @implSpec
    /// The default implementation is:
    /// 
    /// {@snippet :
    ///    // @link substring="start" target="#start(SocketAddress)" region
    ///    // @link substring="InetSocketAddress" target="InetSocketAddress#InetSocketAddress(String, int)" region
    ///    return start(new InetSocketAddress(hostname, port));
    ///    // @end
    ///    // @end
    ///  }
    /// 
    /// @param hostname the host name
    /// @param port the port number
    /// 
    /// @return never normally
    /// 
    /// @throws NullPointerException
    ///             if `hostname` is `null`
    /// @throws IllegalStateException
    ///             if the server is already running
    /// @throws IOException
    ///             if an I/O error occurs
    /// @throws InterruptedException
    ///             if interrupted while waiting on client connections to terminate
    default Void start(String hostname, int port) throws IOException, InterruptedException {
        return start(new InetSocketAddress(hostname, port));
    }
    
    /// Listens for client connections on the specified address.
    /// 
    /// @param address the address
    /// 
    /// @return never normally
    /// 
    /// @throws NullPointerException
    ///             if `address` is `null`
    /// @throws IllegalStateException
    ///             if the server is already running
    /// @throws IOException
    ///             if an I/O error occurs
    /// @throws InterruptedException
    ///             if interrupted while waiting on client connections to terminate
    /// 
    /// @see InetAddress
    Void start(SocketAddress address) throws IOException, InterruptedException;
    
    /// Closes the port listening for client connections.
    /// 
    /// This method will also close all client connections that are not being
    /// used, and signal active HTTP exchanges to close the client connection
    /// after the final response.
    /// 
    /// The thread invoking this method, and the thread blocked in
    /// a `start(...)` method or a thread waiting on the `Future` from
    /// [#startAsync()] to complete, will only return when the last client
    /// connection is closed. Which thread returns first is not defined.
    /// 
    /// In theory, using this method can result in both threads waiting forever
    /// for the completion of HTTP exchanges (the server guards against stale
    /// exchanges not making any progress but does not impose a maximum runtime
    /// length).
    /// 
    /// If this is not desired, consider using one of the other `stop(...)`
    /// methods which allows for a graceful period to be defined.
    /// 
    /// This method is NOP if the server is stopping or has already stopped.
    ///
    /// @throws IOException
    ///             if an I/O error occurs
    /// @throws InterruptedException
    ///             if interrupted while waiting on client connections to terminate
    void stop() throws IOException, InterruptedException;
    
    /// Closes the port listening for client connections.
    /// 
    /// This method is equivalent to [#stop()], except it defines a graceful
    /// period — during which the threads await an orderly close of all client
    /// connections — ends after the given timeout, at which point all client
    /// connections will close.
    /// 
    /// This method has no effect if the server has already stopped.
    /// 
    /// If the server is in the process of stopping, then this method could, in
    /// theory, overwrite a previously set timeout.
    ///
    /// @param timeout when to forcibly close all client connections
    ///
    /// @throws NullPointerException
    ///             if `timeout` is `null`
    /// @throws IOException
    ///             if an I/O error occurs
    /// @throws InterruptedException
    ///             if interrupted while waiting on client connections to terminate
    void stop(Duration timeout) throws IOException, InterruptedException;
    
    /// Closes the port listening for client connections.
    /// 
    /// This method is equivalent to [#stop(Duration)], except the end of the
    /// graceful period is specified using an [Instant].
    ///
    /// @param deadline when to forcibly close all client connections
    ///
    /// @throws NullPointerException
    ///             if `deadline` is `null`
    /// @throws IOException
    ///             if an I/O error occurs
    /// @throws InterruptedException
    ///             if interrupted while waiting on client connections to terminate
    void stop(Instant deadline) throws IOException, InterruptedException;
    
    /// Closes the port listening for client connections.
    /// 
    /// This method will also close all client connections, regardless if the
    /// connection is being used by an active HTTP exchange.
    /// 
    /// This method has no effect if the server has already stopped.
    /// 
    /// If the server is in the process of stopping, then this method will void
    /// a previously set deadline for a graceful period.
    /// 
    /// As is the case with all `stop(...)` methods; this method will only
    /// return once all client connections have closed.
    ///
    /// @throws IOException
    ///             if an I/O error occurs
    /// @throws InterruptedException
    ///             if interrupted while waiting on client connections to terminate
    void kill() throws IOException, InterruptedException;
    
    /// {@return {@code true} if the server is running, otherwise
    ///  {@code false}}
    /// 
    /// By running means, is the server listening on a port for new client
    /// connections?
    /// 
    /// This method does not take into account the state of client connections.
    /// 
    /// Only a return from a thread waiting in a `start(...)` method or on the
    /// completion of the `Future` from [#startAsync()], indicates the end of
    /// all client connections.
    /// 
    /// The answer is an approximated assumption; this method does not probe the
    /// server channel's actual state.
    /// 
    /// A `true` return value is always correct: this method will never
    /// return `true` before the server's channel has been bound, and the
    /// method will never return `true` after the channel has been
    /// closed.
    /// 
    /// This method could return a false `false` only during a minuscule
    /// time window when another thread is executing `stop(...)/kill()` and was
    /// scheduled to close the channel.
    /// 
    /// Or in other words, a `false` return value means that the server is not
    /// running, or it is just about to stop within the blink of an eye.
    boolean isRunning();
    
    /// {@return the event hub associated with this server}
    /// 
    /// The event hub can be used to subscribe to server-related events:
    /// 
    /// {@snippet :
    ///    HttpServer server = ...
    ///    // @link substring="HttpServerStarted" target="HttpServerStarted" :
    ///    server.events().on(HttpServerStarted.class,
    ///            (event, when) -> System.out.println("Server started at " + when));
    ///  }
    /// 
    /// Here's how to emit application-specific events, from code running within
    /// the server:
    /// 
    /// {@snippet :
    ///    // @link substring="httpServer" target="ScopedValues#httpServer()" region
    ///    // @link substring="dispatch" target="EventHub#dispatch(Object)" region
    ///    httpServer().events().dispatch("Hello");
    ///    // @end
    ///    // @end
    ///  }
    /// 
    /// The hub is not bound to the running state of the server: it can be used
    /// before the server has started, while the server is running and after the
    /// server has stopped.
    /// 
    /// Hubs can be combined:
    /// 
    /// {@snippet :
    ///    EventHub one = server1.events(),
    ///             two = server2.events(),
    ///             // @link substring="combine" target="EventHub#combine(ScatteringEventEmitter, ScatteringEventEmitter, ScatteringEventEmitter...)" :
    ///             all = EventHub.combine(one, two);
    ///  }
    /// 
    /// All event objects emitted by the HttpServer is an enum instance and does
    /// not contain event-specific information (e.g.
    /// [HttpServerStarted#INSTANCE]).
    /// 
    /// The event metadata is passed as attachments:
    /// 
    /// <table class="striped">
    ///   <caption style="display:none">Events emitted</caption>
    ///   <thead>
    ///   <tr>
    ///     <th scope="col">Type</th>
    ///     <th scope="col">Attachment 1</th>
    ///     <th scope="col">Attachment 2</th>
    ///   </tr>
    ///   </thead>
    ///   <tbody>
    ///   <tr>
    ///     <th scope="row"> {@link HttpServerStarted} </th>
    ///     <td> {@link Instant} </td>
    ///     <td> {@code null} </td>
    ///   </tr>
    ///   <tr>
    ///     <th scope="row"> {@link HttpServerStopped} </th>
    ///     <td> {@code Instant} </td>
    ///     <td> {@code Instant} </td>
    ///   </tr>
    ///   <tr>
    ///     <th scope="row"> {@link RequestHeadReceived} </th>
    ///     <td> {@link RawRequest.Head} </td>
    ///     <td> {@link RequestHeadReceived.Stats} </td>
    ///   </tr>
    ///   <tr>
    ///     <th scope="row"> {@link ResponseSent} </th>
    ///     <td> {@link Response} </td>
    ///     <td> {@link ResponseSent.Stats} </td>
    ///   </tr>
    ///   </tbody>
    /// </table>
    /// 
    /// @see EventEmitter
    EventHub events();
    
    /// {@return the server's configuration}
    Config getConfig();
    
    /// {@return the socket address this server's channel's socket is bound to}
    /// 
    /// @throws IllegalStateException
    ///             if the server is not running
    /// @throws IOException
    ///             if an I/O error occurs
    /// @see ServerSocketChannel#getLocalAddress()
    SocketAddress getLocalAddress() throws IOException;
    
    /// {@return the port this server is listening to}
    /// 
    /// @implSpec
    /// The default implementation is:
    /// 
    /// {@snippet :
    ///    // @link substring="InetSocketAddress" target="InetSocketAddress" region
    ///    // @link substring="getLocalAddress" target="#getLocalAddress()" region
    ///    // @link substring="getPort" target="InetSocketAddress#getPort()" region
    ///    return ((InetSocketAddress) getLocalAddress()).getPort();
    ///    // @end
    ///    // @end
    ///    // @end
    ///  }
    /// 
    /// @throws IllegalStateException
    ///             if the server is not running
    /// @throws ClassCastException
    ///             if the server was created using an
    ///             [UnixDomainSocketAddress]
    /// @throws IOException
    ///             if an I/O error occurs
    default int getPort() throws IOException {
        return ((InetSocketAddress) getLocalAddress()).getPort();
    }
}
