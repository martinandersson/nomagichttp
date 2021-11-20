package alpha.nomagichttp.internal;

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

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ShutdownChannelGroupException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static alpha.nomagichttp.internal.AtomicReferences.lazyInitOrElse;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static java.time.Duration.ofMinutes;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.concurrent.CompletableFuture.completedStage;
import static java.util.concurrent.CompletableFuture.failedStage;

/**
 * A fully JDK-based and asynchronous implementation of {@code HttpServer}.<p>
 * 
 * The server code use no native calls and it does not use selector threads
 * (event polling) or any other type of blocking techniques. It responds to
 * native system events with zero blocking. All put together translates to
 * maximum performance across all operating systems that runs Java.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class DefaultServer implements HttpServer
{
    private static final System.Logger LOG
            = System.getLogger(DefaultServer.class.getPackageName());
    
    /** Initial capacity of storage for children connections. */
    private static final int INITIAL_CAPACITY = 10_000;
    
    /** To prevent leaks (by this class), children are stored using weak references. */
    private static final long CLEAN_INTERVAL_MIN = ofMinutes(1).toNanos(),
                              CLEAN_INTERVAL_MAX = ofMinutes(1).plusSeconds(30).toNanos();
    
    private final Config config;
    private final DefaultActionRegistry actions;
    private final DefaultRouteRegistry routes;
    private final List<ErrorHandler> eh;
    private final AtomicReference<CompletableFuture<ParentWithHandler>> parent;
    private final EventHub events;
    
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
        this.parent  = new AtomicReference<>();
        this.events  = new DefaultEventHub();
    }
    
    @Override
    public HttpServer start(SocketAddress address) throws IOException {
        requireNonNull(address);
        var res = lazyInitOrElse(
                parent, CompletableFuture::new, v -> initialize(address, v), null);
        
        if (res == null) {
            throw new IllegalStateException("Server already running.");
        }
        
        try {
            // result immediately available because thread was initializer
            ParentWithHandler pwh = res.join();
            pwh.startAccepting();
        } catch (Throwable t) {
            IOException io = null;
            if (t instanceof CompletionException ce) {
                if (ce.getCause() instanceof IOException) {
                    // Unwrap
                    io = (IOException) ce.getCause();
                }
            }
            
            try {
                stopNow();
            } catch (Throwable next) {
                requireNonNullElse(io, t).addSuppressed(next);
            }
            
            if (io != null) {
                throw io;
            } else {
                throw t;
            }
        }
        
        return this;
    }
    
    private void initialize(SocketAddress addr, CompletableFuture<ParentWithHandler> v) {
        final ParentWithHandler pw;
        try {
            pw = initReg(addr);
        } catch (Throwable t) {
            v.completeExceptionally(t);
            return;
        }
        LOG.log(INFO, () -> "Opened server channel: " + pw.channel());
        v.complete(pw);
        events().dispatch(HttpServerStarted.INSTANCE, pw.started());
    }
    
    private ParentWithHandler initReg(SocketAddress addr) throws Throwable {
        var grp = AsyncGroup.register(getConfig().threadPoolSize());
        try {
            return initOpen(grp, addr);
        } catch (Throwable t) {
            AsyncGroup.unregister();
            throw t;
        }
    }
    
    private ParentWithHandler initOpen(AsynchronousChannelGroup grp, SocketAddress addr) throws Throwable {
        var ch = AsynchronousServerSocketChannel.open(grp);
        try {
            ch.bind(addr);
            final Instant when = Instant.now();
            return new ParentWithHandler(ch, when);
        } catch (Throwable t) {
            ch.close();
            throw t;
        }
    }
    
    @Override
    public CompletionStage<Void> stop() {
        ParentWithHandler pwh;
        
        try {
            pwh = stopServer();
        } catch (IOException e) {
            return failedStage(e);
        }
        
        return pwh == null ?
                completedStage(null) :
                pwh.handler().lastChildStage();
    }
    
    @Override
    public void stopNow() throws IOException {
        ParentWithHandler pwh = stopServer();
        if (pwh != null) {
            pwh.handler().closeAllChildren();
        }
    }
    
    @Override
    public boolean isRunning() {
        return getParent(false) != null;
    }
    
    private ParentWithHandler stopServer() throws IOException {
        ParentWithHandler pwh = getParent(true);
        if (pwh == null) {
            // great, never started
            return null;
        }
        if (pwh.markTerminated()) {
            parent.set(null);
            AsynchronousServerSocketChannel ch = pwh.channel();
            ch.close();
            final Instant when = Instant.now();
            LOG.log(INFO, () -> "Closed server channel: " + ch);
            AsyncGroup.unregister();
            pwh.handler().tryCompleteLastChildStage();
            events().dispatch(HttpServerStopped.INSTANCE, when, pwh.started());
            return pwh;
        } else {
            return null;
        }
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
    public HttpServer before(String pattern, BeforeAction first, BeforeAction... more) {
        return actions.before(pattern, first, more);
    }
    
    @Override
    public HttpServer after(String pattern, AfterAction first, AfterAction... more) {
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
    public InetSocketAddress getLocalAddress() throws IllegalStateException, IOException {
        try {
            var addr = parent.get().getNow(null).channel().getLocalAddress();
            return (InetSocketAddress) requireNonNull(addr);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) { // including NPE
            assert !(e instanceof ClassCastException);
            throw new IllegalStateException("Server is not running.", e);
        }
    }
    
    private ParentWithHandler getParent(boolean waitOnBoot) {
        var res = parent.get();
        if (res == null) {
            return null;
        }
        
        if (!waitOnBoot && !res.isDone()) {
            return null;
        }
        
        final ParentWithHandler pwh;
        try {
            pwh = res.join();
        } catch (CancellationException | CompletionException e) {
            return null;
        }
        
        return pwh;
    }
    
    private final class ParentWithHandler {
        private final AsynchronousServerSocketChannel parent;
        private final Instant started;
        private final OnAccept onAccept;
        private final AtomicBoolean terminated;
        
        ParentWithHandler(AsynchronousServerSocketChannel parent, Instant started) {
            this.parent     = parent;
            this.started    = started;
            this.onAccept   = new OnAccept(parent);
            this.terminated = new AtomicBoolean();
        }
        
        AsynchronousServerSocketChannel channel() {
            return parent;
        }
        
        Instant started() {
            return started;
        }
        
        OnAccept handler() {
            return onAccept;
        }
        
        void startAccepting() {
            parent.accept(null, onAccept);
            onAccept.scheduleBackgroundCleaning();
        }
        
        boolean markTerminated() {
            return terminated.compareAndSet(false, true);
        }
    }
    
    private class OnAccept implements CompletionHandler<AsynchronousSocketChannel, Void>
    {
        private final AsynchronousServerSocketChannel parent;
        private final Set<WeakReference<DefaultClientChannel>> children;
        private final CompletableFuture<Void> lastChild;
        
        OnAccept(AsynchronousServerSocketChannel parent) {
            this.parent    = parent;
            this.children  = ConcurrentHashMap.newKeySet(INITIAL_CAPACITY);
            this.lastChild = new CompletableFuture<>();
        }
        
        CompletionStage<Void> lastChildStage() {
            return lastChild.copy();
        }
        
        void scheduleBackgroundCleaning() {
            var delay = ThreadLocalRandom.current().nextLong(
                    CLEAN_INTERVAL_MIN, CLEAN_INTERVAL_MAX + 1);
            Timeout.schedule(delay, this::clean);
        }
        
        private void clean() {
            boolean mayAcceptMore = parent.isOpen();
            children.forEach(ref -> {
                DefaultClientChannel chApi = ref.get();
                if (chApi == null) {
                    if (children.remove(ref)) {
                        LOG.log(WARNING, "Unexpectedly, empty weak child reference found (and removed).");
                    }
                } else if (!chApi.isAnythingOpen()) {
                    if (children.remove(ref)) {
                        // Not a WARNING; race between this task and setup() -> chApi.onClose()
                        LOG.log(DEBUG, "Unexpectedly, closed child found (and removed).");
                    }
                }
            });
            if (mayAcceptMore) {
                scheduleBackgroundCleaning();
            } else {
                tryCompleteLastChildStage();
            }
        }
        
        void closeAllChildren() {
            assert !parent.isOpen();
            // Weakly consistent, so go again if not empty
            while (!children.isEmpty()) {
                children.forEach(ref -> {
                    ClientChannel chApi;
                    if (children.remove(ref) && ((chApi = ref.get()) != null)) {
                        chApi.closeSafe();
                    }
                });
            }
            tryCompleteLastChildStage();
        }
        
        // Notify anyone waiting on the last child
        // (assumes server channel is closed)
        void tryCompleteLastChildStage() {
            if (children.isEmpty()) {
                lastChild.complete(null);
            }
        }
        
        @Override
        public void completed(AsynchronousSocketChannel child, Void noAttachment) {
            try {
                parent.accept(null, this);
            } catch (Throwable t) {
                LOG.log(DEBUG, () -> "Discarding child.");
                try {
                    child.close();
                } catch (IOException | ShutdownChannelGroupException e) {
                    // Ignore
                } catch (Throwable next) {
                    LOG.log(WARNING, "Unknown (and ignored) exception from discarding close() call.", next);
                }
                failed(t, null);
                return;
            }
            setup(child);
        }
        
        private void setup(AsynchronousSocketChannel child) {
            LOG.log(DEBUG, () -> "Accepted child: " + child);
            
            DefaultClientChannel chApi = new DefaultClientChannel(child, DefaultServer.this);
            ChannelByteBufferPublisher chIn = new ChannelByteBufferPublisher(chApi);
            var ref = new WeakReference<>(chApi);
            children.add(ref);
            chApi.onClose(() -> {
                children.remove(ref);
                if (!parent.isOpen()) {
                    tryCompleteLastChildStage();
                }
            });
            
            startExchange(chApi, chIn);
            
            // TODO: child.setOption(StandardSocketOptions.SO_KEEPALIVE, true); ??
        }
        
        private void startExchange(
                DefaultClientChannel chApi,
                ChannelByteBufferPublisher chIn)
        {
            var exch = new HttpExchange(
                    DefaultServer.this, actions, routes, eh, chIn, chApi);
            
            exch.begin().whenComplete((nil, exc) -> {
                // Both open-calls are volatile reads, no locks
                if (exc == null && parent.isOpen() && chApi.isEverythingOpen()) {
                    startExchange(chApi, chIn);
                } else {
                    chApi.closeSafe();
                }
            });
        }
        
        @Override
        public void failed(Throwable t, Void noAttachment) {
            if (becauseChannelOrGroupClosed(t)) {
                return;
            }
            LOG.log(ERROR, "Unexpected or unknown failure. Stopping server (without force-closing children).", t);
            stop();
        }
    }
    
    /**
     * Returns {@code true} if the channel operation failed because the channel
     * or the group to which the channel belongs, was closed - otherwise {@code
     * false}.<p>
     * 
     * For a long time, these errors were observed only on failed accept and
     * read/write initiating operations. Only once on MacOS, however, was a
     * {@code ShutdownChannelGroupException} also observed on a child channel
     * {@code close()}.
     * 
     * @param t throwable to test
     * 
     * @return see JavaDoc
     */
    static boolean becauseChannelOrGroupClosed(Throwable t) {
        return t instanceof ClosedChannelException || // note: AsynchronousCloseException extends ClosedChannelException
               t instanceof ShutdownChannelGroupException ||
               t instanceof IOException && t.getCause() instanceof ShutdownChannelGroupException;
    }
}