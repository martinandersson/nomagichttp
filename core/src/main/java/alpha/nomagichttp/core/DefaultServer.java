package alpha.nomagichttp.core;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.event.DefaultEventHub;
import alpha.nomagichttp.event.EventHub;
import alpha.nomagichttp.event.HttpServerStarted;
import alpha.nomagichttp.event.HttpServerStopped;
import alpha.nomagichttp.handler.ClientChannel;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.IntConsumer;

import static alpha.nomagichttp.core.Timeout.schedule;
import static alpha.nomagichttp.core.VThreads.CHANNEL_BLOCKING;
import static alpha.nomagichttp.util.Blah.getOrClose;
import static alpha.nomagichttp.util.Blah.runOrClose;
import static alpha.nomagichttp.util.ScopedValues.CHANNEL;
import static alpha.nomagichttp.util.ScopedValues.HTTP_SERVER;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.Thread.startVirtualThread;
import static java.net.InetAddress.getLoopbackAddress;
import static java.net.StandardProtocolFamily.UNIX;
import static java.nio.channels.ServerSocketChannel.open;
import static java.time.Instant.now;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static jdk.incubator.concurrent.ScopedValue.where;

/**
 * A fully JDK-based {@code HttpServer} implementation using virtual threads.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class DefaultServer implements HttpServer
{
    private static final int INITIAL_CAPACITY = 100;
    
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
    private final Map<ClientChannel, ChannelReader> children;
    private       Instant started;
    // Memory consistency not specified between
    //     ServerSocketChannel.close() and AsynchronousCloseException
    private volatile Instant waitForChildren;
    
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
                () -> !HTTP_SERVER.isBound(),
                r -> where(HTTP_SERVER, this, r));
        this.parent  = new Confined<>();
        this.terminated = new CountDownLatch(1);
        this.children = new ConcurrentHashMap<>(INITIAL_CAPACITY);
        this.started = null;
        this.waitForChildren = null;
    }
    
    @Override
    public Void start(IntConsumer ofPort)
            throws IOException, InterruptedException {
        start0(loopBackSystemPickedPort(), requireNonNull(ofPort));
        // start0() returns exceptionally!
        throw newDeadCode();
    }
    
    @Override
    public Void start(SocketAddress address)
            throws IOException, InterruptedException {
        start0(requireNonNull(address), null);
        throw newDeadCode();
    }
    
    private void start0(SocketAddress addr, IntConsumer ofPort)
            throws IOException, InterruptedException
    {
        try (ServerSocketChannel ch = openOrFail(addr)) {
            if (ofPort != null) {
                int port = getPort();
                where(HTTP_SERVER, this, () -> ofPort.accept(port));
            }
            runAcceptLoop(ch);
        }
        assert false;
    }
    
    @Override
    public Future<Void> startAsync() throws IOException {
        final var fut = new CompletableFuture<Void>();
        final ServerSocketChannel ch
                = openOrFail(loopBackSystemPickedPort());
        runOrClose(() ->
            startVirtualThread(() -> {
                try {
                    runAcceptLoop(ch);
                } catch (Throwable t) {
                    fut.completeExceptionally(t);
                    return;
                }
                fut.completeExceptionally(newDeadCode());
        }), ch);
        return getOrClose(() ->
            fut.whenComplete((isNull, thrFromLoop) -> {
                try {
                    ch.close();
                } catch (Throwable fromClose) {
                    thrFromLoop.addSuppressed(fromClose);
                }
            }), ch);
    }
    
    private ServerSocketChannel openOrFail(SocketAddress addr)
            throws IOException {
        var ssc2 = this.parent.initThrowsX(() -> {
            var ssc1 = addr instanceof UnixDomainSocketAddress ?
                    open(UNIX) : open();
            runOrClose(() -> {
                assert ssc1.isBlocking() : CHANNEL_BLOCKING;
                ssc1.bind(addr);
                started = now();
                LOG.log(INFO, () -> "Opened server channel: " + ssc1);
                events().dispatch(HttpServerStarted.INSTANCE, started);
            }, ssc1);
            return ssc1;
        });
        if (ssc2 == null) {
            throw new IllegalStateException("Server has started once before");
        }
        return ssc2;
    }
    
    // Bind the server instance and translate where's "Exception" to our signature
    private void runAcceptLoop(ServerSocketChannel ch)
            throws IOException, InterruptedException
    {
        try {
            where(HTTP_SERVER, this, () -> runAcceptLoop0(ch));
        } catch (Exception e) {
            switch (e) {
                case IOException t -> throw t;
                case InterruptedException t -> throw t;
                default -> throw new AssertionError(e);
            }
        }
        assert false;
    }
    
    // Manage the loop-enclosing scope; graceful stop before closing
    private Void runAcceptLoop0(ServerSocketChannel parent)
            throws IOException, InterruptedException
    {
        try (var scope = new StructuredTaskScope<>()) {
            try {
                runAcceptLoop1(parent, scope);
                throw newDeadCode();
            } finally {
                closeParent();
                closeIdlingChildren();
                /*
                 * There is a race between the thread stopping the server
                 * (invoking this method), and the threads that modifies the
                 * child map; that is to say, the thread that accepts a new
                 * connection, and the thread beginning a new exchange.
                 *     The race is benign, because the scope will shut down no
                 * later than at the end of the graceful period.
                 *     What can be the effect of the race, is having the thread
                 * that started and the thread that is stopping the server be
                 * blocked unnecessarily long.
                 *     Closing idling children just one more time, ought to
                 * unblock 99% of the cases. The block was actually quite common
                 * in medium compatibility tests on GitHub Action's MacOS and
                 * Linux workers.
                 */
                schedule(MILLISECONDS.toNanos(100), this::closeIdlingChildren);
                join(scope);
            }
        } finally {
            terminated.countDown();
            assert children.isEmpty();
        }
    }
    
    private void runAcceptLoop1(
            ServerSocketChannel parent, StructuredTaskScope<?> scope)
            throws IOException
    {
        // The loop can not complete without throwing an exception (from accept)
        for (;;) {
            var child = parent.accept();
            // Reader and writer depend on blocking mode for correct behavior
            assert child.isBlocking() : CHANNEL_BLOCKING;
            runOrClose(() -> scope.fork(() -> {
                try {
                    runHttpExchanges(child);
                } catch (Throwable t) {
                    if (!(t instanceof Exception)) { // Throwable, Error
                        // Virtual threads do not log anything, we're more kind
                        LOG.log(ERROR, "Unexpected", t);
                    }
                    throw t;
                }
                return null;
            }), child);
        }
    }
    
    private void runHttpExchanges(SocketChannel ch) {
        final var api = new DefaultClientChannel(ch);
        var r = new ChannelReader(ch);
        children.put(api, r);
        try {
            LOG.log(DEBUG, () -> "Accepted child: " + ch);
            // Exchange loop; breaks when a new exchange should not begin
            for (;;) {
                var w = new DefaultChannelWriter(ch, actions);
                api.use(w);
                var exch = new HttpExchange(
                        this, actions, routes, eh, api, r, w);
                where(CHANNEL, api, exch::begin);
                r.dismiss();
                w.dismiss();
                // ResponseProcessor will set "Connection: close" if !isRunning()
                if (api.areBothStreamsOpen()) {
                    r = r.newReader();
                    children.put(api, r);
                } else {
                    break;
                }
            }
        } finally {
            children.remove(api);
            if (api.isAnyStreamOpen()) {
                LOG.log(DEBUG, () -> "Closing child: " + ch);
                api.close();
            }
        }
    }
    
    private void closeParent() throws IOException {
        this.parent.dropThrowsX(channel -> {
            LOG.log(INFO, () -> "Closing server channel: " + channel);
            try {
                channel.close();
            } finally {
                assert !channel.isOpen();
                events().dispatch(HttpServerStopped.INSTANCE, now(), started);
            }
        });
    }
    
    /**
     * Closes all children associated with an exchange that has not started.<p>
     * 
     * The purpose of this method is to not stall the threads running and
     * stopping the server more than necessary.<p>
     * 
     * The server having been stopped is what signals no new exchanges to
     * commence over the same connection. This is implemented by
     * {@link ResponseProcessor} (which checks the server's running state before
     * approving a new exchange).
     */
    private void closeIdlingChildren() {
        int size = 0, closed = 0;
        for (var entry : children.entrySet()) {
            ++size;
            var reader = entry.getValue();
            var api = entry.getKey();
            if (reader.hasNotStarted()) {
                api.close();
                ++closed;
            }
        }
        if (size > 0 && LOG.isLoggable(DEBUG)) {
            LOG.log(DEBUG,
              "Closed %s idling children of a total %s.".formatted(closed, size));
        }
    }
    
    /** Shutdown then join, or joinUntil graceful timeout. */
    private void join(StructuredTaskScope<?> scope) throws InterruptedException {
        var wfc = waitForChildren;
        if (wfc == null) {
            // On shutdown, children should observe:
            //     java.net.ClosedByInterruptException
            LOG.log(DEBUG, "No graceful period; shutting down scope.");
            scope.shutdown();
            scope.join();
        } else {
            try {
                scope.joinUntil(wfc);
                LOG.log(DEBUG, "All exchanges finished within the graceful period.");
            } catch (TimeoutException ignored) {
                LOG.log(DEBUG, "Graceful deadline expired; shutting down scope.");
                // Enclosing try-with-resources > scope.close() > scope.shutdown()
            }
        }
    }
    
    @Override
    public void stop() throws IOException, InterruptedException {
        stop(Instant.MAX);
    }
    
    @Override
    public void stop(Duration timeout)
            throws IOException, InterruptedException {
        stop(now().plus(timeout));
    }
    
    @Override
    public void stop(Instant deadline)
            throws IOException, InterruptedException {
        waitForChildren = requireNonNull(deadline);
        closeParent();
        terminated.await();
    }
    
    @Override
    public void kill()
            throws IOException, InterruptedException {
        waitForChildren = null;
        closeParent();
        // No rolling back the kill switch 😂
        children.forEach((api, reader) -> api.close());
        terminated.await();
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
    
    private static InternalError newDeadCode() {
        return new InternalError("Dead code");
    }
    
    private static SocketAddress loopBackSystemPickedPort() {
        return new InetSocketAddress(getLoopbackAddress(), 0);
    }
    
    private static IllegalStateException notRunning() {
        return new IllegalStateException("Server is not running");
    }
}