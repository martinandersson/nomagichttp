package alpha.nomagichttp.internal;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.events.DefaultEventHub;
import alpha.nomagichttp.events.EventHub;
import alpha.nomagichttp.events.HttpServerStarted;
import alpha.nomagichttp.events.HttpServerStopped;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteRegistry;

import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static alpha.nomagichttp.internal.AtomicReferences.lazyInitOrElse;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Objects.requireNonNull;
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
    
    private static final int INITIAL_CAPACITY = 10_000;
    
    private final Config config;
    private final RouteRegistry registry;
    private final List<ErrorHandler> eh;
    private final AtomicReference<CompletableFuture<ParentWithHandler>> parent;
    private final EventHub events;
    
    /**
     * Constructs a {@code DefaultServer}.
     * 
     * @param config of server
     * @param registry of server
     * @param eh error handlers
     */
    public DefaultServer(Config config, RouteRegistry registry, ErrorHandler... eh) {
        this.config   = requireNonNull(config);
        this.registry = requireNonNull(registry);
        this.eh       = List.of(eh);
        this.parent   = new AtomicReference<>();
        this.events   = new DefaultEventHub();
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
            if (t instanceof CompletionException) {
                CompletionException ce = (CompletionException) t;
                if (ce.getCause() instanceof IOException) {
                    // Unwrap
                    io = (IOException) ce.getCause();
                }
            }
            
            try {
                stopNow();
            } catch (Throwable next) {
                if (io != null) {
                    io.addSuppressed(next);
                } else {
                    t.addSuppressed(next);
                }
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
        final AsynchronousChannelGroup grp;
        try {
            grp = AsyncGroup.register(getConfig().threadPoolSize());
        } catch (IOException e) {
            v.completeExceptionally(e);
            return;
        }
        
        final AsynchronousServerSocketChannel ch;
        final Instant when;
        try {
            ch = AsynchronousServerSocketChannel.open(grp).bind(addr);
            when = Instant.now();
        } catch (Throwable t) {
            AsyncGroup.unregister();
            v.completeExceptionally(t);
            return;
        }
        
        LOG.log(INFO, () -> "Opened server channel: " + ch);
        v.complete(new ParentWithHandler(ch, when));
        events().dispatch(HttpServerStarted.INSTANCE, when);
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
        registry.add(route);
        return this;
    }
    
    @Override
    public Route remove(String pattern) {
        return registry.remove(pattern);
    }
    
    @Override
    public boolean remove(Route route) {
        return registry.remove(route);
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
            return (InetSocketAddress) parent.get().join().channel().getLocalAddress();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) { // including NPE from get().join()
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
        }
        
        boolean markTerminated() {
            return terminated.compareAndSet(false, true);
        }
    }
    
    private class OnAccept implements CompletionHandler<AsynchronousSocketChannel, Void>
    {
        private final AsynchronousServerSocketChannel parent;
        private final Set<DefaultClientChannel> children;
        private final CompletableFuture<Void> lastChild;
        
        OnAccept(AsynchronousServerSocketChannel parent) {
            this.parent    = parent;
            this.children  = ConcurrentHashMap.newKeySet(INITIAL_CAPACITY);
            this.lastChild = new CompletableFuture<>();
        }
        
        void closeAllChildren() {
            while (!children.isEmpty()) {
                children.forEach(c -> {
                    if (children.remove(c)) {
                        c.closeSafe();
                    }
                });
            }
            tryCompleteLastChildStage();
        }
        
        CompletionStage<Void> lastChildStage() {
            return lastChild.copy();
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
            
            DefaultClientChannel chan = new DefaultClientChannel(child, DefaultServer.this);
            ChannelByteBufferPublisher bytes = new ChannelByteBufferPublisher(chan);
            children.add(chan);
            // TODO: Wanna complete only when the LAST async I/O operation completes
            //       (will likely require 1 ChannelByteBufferSubscriber per channel?)
            chan.onClose(() -> {
                children.remove(chan);
                if (!parent.isOpen()) {
                    tryCompleteLastChildStage();
                }
            });
            
            startExchange(chan, bytes);
            
            // TODO: child.setOption(StandardSocketOptions.SO_KEEPALIVE, true); ??
        }
        
        private void startExchange(
                DefaultClientChannel chan,
                ChannelByteBufferPublisher bytes)
        {
            var exch = new HttpExchange(
                    DefaultServer.this, registry, eh, bytes, chan);
            
            exch.begin().whenComplete((Null, exc) -> {
                // Both open-calls are volatile reads, no locks
                if (exc == null && parent.isOpen() && chan.isEverythingOpen()) {
                    startExchange(chan, bytes);
                } else {
                    chan.closeSafe();
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