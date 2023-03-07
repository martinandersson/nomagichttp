package alpha.nomagichttp.internal;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.event.DefaultEventHub;
import alpha.nomagichttp.event.EventHub;
import alpha.nomagichttp.event.HttpServerStarted;
import alpha.nomagichttp.event.HttpServerStopped;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.route.Route;
import jdk.incubator.concurrent.StructuredTaskScope;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.IntConsumer;

import static alpha.nomagichttp.util.DummyScopedValue.where;
import static alpha.nomagichttp.util.ScopedValues.__CHANNEL;
import static alpha.nomagichttp.util.ScopedValues.__HTTP_SERVER;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.net.InetAddress.getLoopbackAddress;
import static java.net.StandardProtocolFamily.UNIX;
import static java.nio.channels.ServerSocketChannel.open;
import static java.time.Instant.now;
import static java.util.Objects.requireNonNull;

/**
 * A fully JDK-based {@code HttpServer} implementation using virtual threads.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class DefaultServer implements HttpServer
{
    private static final System.Logger LOG
            = System.getLogger(DefaultServer.class.getPackageName());
    
    private final Config config;
    private final DefaultActionRegistry actions;
    private final DefaultRouteRegistry routes;
    private final List<ErrorHandler> eh;
    private final EventHub events;
    // Would prefer ServerSocket > Socket > Input/OutputStream,
    //     using channel for direct transfer operations and bytebuffers
    private final Confined<ServerSocketChannel> parent;
    private final CountDownLatch terminated;
    private       Instant started;
    // Memory consistency not specified between
    //     ServerSocketChannel.close() and AsynchronousCloseException
    private volatile Object waitForChildren;
    
    /**
     * Constructs a {@code DefaultServer}.
     * 
     * @param config of server
     * @param eh error handlers
     */
    public DefaultServer(Config config, ErrorHandler... eh) {
        this.config  = requireNonNull(config);
        this.actions = new DefaultActionRegistry(this);
        this.routes  = new DefaultRouteRegistry(this);
        this.eh      = List.of(eh);
        this.events  = new DefaultEventHub(
                () -> !__HTTP_SERVER.isBound(),
                r -> where(__HTTP_SERVER, this, r));
        this.parent  = new Confined<>();
        this.terminated = new CountDownLatch(1);
        this.started = null;
        this.waitForChildren = null;
    }
    
    @Override
    public Void start(IntConsumer ofPort)
            throws IOException, InterruptedException {
        return start0(lookBackSystemPickedPort(), requireNonNull(ofPort), null);
    }
    
    @Override
    public Future<Void> startAsync() throws IOException {
        // TODO: The fut-pill is a bit messy, we may need to rework this.
        //       I'd just like to have solid life-cycle tests in place first.
        var fut = new CompletableFuture<Void>();
        try {
            start0(lookBackSystemPickedPort(), null, fut);
        } catch (InterruptedException e) {
            throw new InternalError(e);
        }
        return fut;
    }
    
    private static SocketAddress lookBackSystemPickedPort() {
        return new InetSocketAddress(getLoopbackAddress(), 0);
    }
    
    @Override
    public Void start(SocketAddress address)
            throws IOException, InterruptedException {
        return start0(requireNonNull(address), null, null);
    }
    
    private Void start0(
            SocketAddress addr, IntConsumer ofPort, CompletableFuture fut)
            throws IOException, InterruptedException
    {
        var parent = this.parent.initThrowsX(() -> {
            var p = addr instanceof UnixDomainSocketAddress ?
                        open(UNIX) : open();
            assert p.isBlocking();
            p.bind(addr);
            return p;
        });
        if (parent == null) {
            throw new IllegalStateException("Server has started once before");
        }
        try {
            started = now();
            LOG.log(INFO, () -> "Opened server channel: " + parent);
            events().dispatch(HttpServerStarted.INSTANCE, started);
            if (ofPort != null) {
                int port = getPort();
                where(__HTTP_SERVER, this, () -> ofPort.accept(port));
            }
            if (fut == null) {
                runAcceptLoop(parent);
                throw new InternalError("Returns exceptionally");
            } else {
                Thread.startVirtualThread(() -> {
                    try {
                        runAcceptLoop(parent);
                    } catch (Throwable t) {
                        fut.completeExceptionally(t);
                    }
                });
                return null;
            }
        } finally {
            // Can not stop() as this could override app's waitForChildren
            close();
        }
    }
    
    private void runAcceptLoop(ServerSocketChannel parent)
            throws IOException, InterruptedException
    {
        try {
            where(__HTTP_SERVER, this, () -> runAcceptLoop0(parent));
        } catch (Exception e) {
            switch (e) {
                // From accept() or close()
                case IOException t -> throw t;
                // From exit()
                case InterruptedException t -> throw t;
                // Would only expect StructureViolationException at this point
                default -> throw new AssertionError(e);
            }
        }
    }
    
    private Void runAcceptLoop0(ServerSocketChannel parent)
            throws IOException, InterruptedException
    {
        try (var scope = new StructuredTaskScope<>()) {
            try {
                // Accept loop; can not complete without throwing an exception
                for (;;) {
                    var child = parent.accept();
                    // Reader and writer depend on this for correct behavior
                    assert child.isBlocking();
                    LOG.log(DEBUG, () -> "Accepted child: " + child);
                    scope.fork(() -> {
                        try {
                            handleChild(child);
                        } catch (Throwable t) {
                            if (!(t instanceof Exception)) { // Throwable, Error
                                // Virtual threads do not log anything, we're more kind
                                LOG.log(ERROR, "Unexpected", t);
                            }
                            throw t;
                        }
                        return null;
                    });
                }
            } finally {
                // Must signal children we're closing down
                close();
                exit(scope);
            }
        }
    }
    
    private void handleChild(SocketChannel ch) throws IOException {
        try (ch) {
            var api = new DefaultClientChannel(ch);
            var r = new ChannelReader(ch);
            // Exchange loop; breaks when a new exchange should not begin
            for (;;) {
                var w = new DefaultChannelWriter(ch, actions, r);
                api.use(w);
                var exch = new HttpExchange(
                        this, actions, routes, eh, api, r, w);
                where(__CHANNEL, api, exch::begin);
                r.dismiss();
                w.dismiss();
                // DefaultChannelWriter will set "Connection: close" if !isRunning()
                if (api.isInputOpen() && api.isOutputOpen()) {
                    r = r.newReader();
                } else {
                    break;
                }
            }
        }
    }
    
    /** Shutdown then join, or joinUntil then shutdown. */
    private void exit(StructuredTaskScope<?> scope) throws InterruptedException {
        try {
            Object obj = waitForChildren;
            if (obj == null) {
                // On shutdown, children should observe:
                //     java.net.ClosedByInterruptException
                scope.shutdown();
                scope.join();
            } else {
                if (obj instanceof Duration d) {
                    // joinUntil() is going to reverse this lol
                    obj = now().plus(d);
                }
                assert obj.getClass() == Instant.class;
                try {
                    scope.joinUntil((Instant) obj);
                } catch (TimeoutException ignored) {
                    // Enclosing try-with-resources > scope.close() > scope.shutdown()
                }
            }
        } finally {
            terminated.countDown();
        }
    }
    
    @Override
    public void stop() throws IOException, InterruptedException {
        stop(Instant.MAX);
    }
    
    @Override
    public void stop(Instant deadline)
            throws IOException, InterruptedException {
        waitForChildren = requireNonNull(deadline);
        closeAndAwaitChildren();
    }
    
    @Override
    public void stop(Duration timeout)
            throws IOException, InterruptedException {
        waitForChildren = requireNonNull(timeout);
        closeAndAwaitChildren();
    }
    
    // Aliasing for correct semantics and a stacktrace that makes sense for app developer
    @Override
    public void kill()
            throws IOException, InterruptedException {
        waitForChildren = Instant.now();
        closeAndAwaitChildren();
    }
    
    private boolean close() throws IOException {
        return this.parent.dropThrowsX(channel -> {
            try {
                channel.close();
                LOG.log(INFO, () -> "Closed server channel: " + channel);
            } catch (Throwable close) {
                LOG.log(INFO, () -> {
                    var msg = "Attempted to close server channel: ";
                    try {
                        msg += channel;
                    } catch (Throwable toString) {
                        msg += "N/A";
                        close.addSuppressed(toString);
                    }
                    return msg;
                });
                throw close;
            } finally {
                events().dispatch(HttpServerStopped.INSTANCE, now(), started);
            }
        });
    }
    
    private void closeAndAwaitChildren()
            throws IOException, InterruptedException {
        if (close()) {
            terminated.await();
        }
    }
    
    @Override
    public boolean isRunning() {
        return parent.isPresent();
    }
    
    @Override
    public HttpServer add(Route route) {
        return routes.add(route);
    }
    
    @Override
    public Route remove(String pattern) {
        return routes.remove(pattern);
    }
    
    @Override
    public boolean remove(Route route) {
        return routes.remove(route);
    }
    
    @Override
    public HttpServer before(
            String pattern, BeforeAction first, BeforeAction... more) {
        return actions.before(pattern, first, more);
    }
    
    @Override
    public HttpServer after(
            String pattern, AfterAction first, AfterAction... more) {
        return actions.after(pattern, first, more);
    }
    
    @Override
    public EventHub events() {
        return events;
    }
    
    @Override
    public Config getConfig() {
        return config;
    }
    
    @Override
    public SocketAddress getLocalAddress() throws IOException {
        var channel = parent.peek()
                .orElseThrow(DefaultServer::notRunning);
        try {
            var addr = channel.getLocalAddress();
            if (addr == null) {
                // I don't expect this to ever happen,
                // if not bound then we should have received a ClosedChannelException
                throw notRunning();
            }
            return addr;
        } catch (ClosedChannelException e) {
            throw notRunning();
        }
    }
    
    private static IllegalStateException notRunning() {
        return new IllegalStateException("Server is not running");
    }
}