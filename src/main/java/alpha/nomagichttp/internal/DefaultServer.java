package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
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
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static alpha.nomagichttp.internal.AtomicReferences.lazyInitOrElse;
import static alpha.nomagichttp.internal.AtomicReferences.setIfAbsent;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.net.InetAddress.getLoopbackAddress;
import static java.util.Objects.requireNonNull;

/**
 * A fully asynchronous implementation of {@link HttpServer}.<p>
 * 
 * This class uses {@link AsynchronousServerSocketChannel} which provides an
 * amazing performance on many operating systems, including Windows.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class DefaultServer implements HttpServer
{
    private static final System.Logger LOG
            = System.getLogger(DefaultServer.class.getPackageName());
    
    /**
     * Global count of active servers.
     */
    private static final AtomicInteger SERVER_COUNT = new AtomicInteger();
    
    private final Config config;
    private final RouteRegistry registry;
    private final List<ErrorHandler> eh;
    private final AtomicReference<CompletableFuture<AsynchronousServerSocketChannel>> parent;
    private final AtomicReference<CompletableFuture<Void>> waitOnChildrenStop;
    private final Set<DefaultChannelOperations> children;
    private final AtomicBoolean terminating;
    
    /**
     * Constructs a {@code DefaultServer}.
     * 
     * @param config of server
     * @param registry of server
     * @param eh error handlers
     */
    public DefaultServer(
            Config config,
            RouteRegistry registry,
            ErrorHandler... eh)
    {
        this.config        = requireNonNull(config);
        this.registry      = requireNonNull(registry);
        this.eh            = List.of(eh);
        this.parent        = new AtomicReference<>();
        this.waitOnChildrenStop = new AtomicReference<>();
        this.children      = ConcurrentHashMap.newKeySet(10_000);
        this.terminating   = new AtomicBoolean();
    }
    
    @Override
    public HttpServer start(SocketAddress address) throws IOException {
        var res = lazyInitOrElse(
                parent, CompletableFuture::new, v -> initialize(address, v), null);
        
        if (res == null) {
            throw new IllegalStateException("Server already running.");
        }
        
        try {
            // result immediately available because thread was initializer
            AsynchronousServerSocketChannel parent = res.join();
            parent.accept(null, new OnAccept(parent));
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
    
    private void initialize(SocketAddress addr, CompletableFuture<AsynchronousServerSocketChannel> v) {
        final AsynchronousServerSocketChannel ch;
        try {
            SocketAddress use = addr != null? addr :
                    new InetSocketAddress(getLoopbackAddress(), 0);
           
            AsynchronousChannelGroup grp = AsyncGroup.getOrCreate(config.threadPoolSize())
                    .toCompletableFuture().join();
            
            ch = AsynchronousServerSocketChannel.open(grp).bind(use);
        } catch (Throwable t) {
            if (SERVER_COUNT.get() == 0) {
                // benign race with other servers starting in parallel,
                // if they fail because we shutdown the group here, then like whatever
                AsyncGroup.shutdown();
            }
            v.completeExceptionally(t);
            return;
        }
        LOG.log(INFO, () -> "Opened server channel: " + ch);
        SERVER_COUNT.incrementAndGet();
        v.complete(ch);
    }
    
    @Override
    public CompletionStage<Void> stop() {
        CompletableFuture<Void> trigger = new CompletableFuture<>();
        trigger.whenComplete((ign,ored) -> waitOnChildrenStop.set(null));
        
        // trigger may now be our local one, or a previously set value
        trigger = setIfAbsent(waitOnChildrenStop, trigger);
        
        if (stopServerSafe(trigger) && children.isEmpty()) {
            trigger.complete(null);
        }
        
        return trigger.copy();
    }
    
    @Override
    public void stopNow() throws IOException {
        stopServer();
        stopChildren();
    }
    
    private void stopServer() throws IOException {
        var res = parent.get();
        if (res == null) {
            return;
        }
        
        final AsynchronousServerSocketChannel ch;
        try {
            ch = res.join();
        } catch (CancellationException | CompletionException e) {
            // great, never started
            return;
        }
        
        if (terminating.compareAndSet(false, true)) {
            try {
                ch.close();
                LOG.log(INFO, () -> "Closed server channel: " + ch);
                int n = SERVER_COUNT.decrementAndGet();
                parent.set(null);
                if (n == 0) {
                    // benign race
                    AsyncGroup.shutdown();
                }
                assert n >= 0;
            } finally {
                terminating.set(false);
            }
        }
    }
    
    private boolean stopServerSafe(CompletableFuture<Void> reportErrorTo) {
        try {
            stopServer();
            return true;
        } catch (IOException e) {
            if (!reportErrorTo.completeExceptionally(e)) {
                LOG.log(DEBUG,
                    "Stop-Future already completed. " +
                    "Except for this debug log, this error is ignored.", e);
            }
            return false;
        }
    }
    
    private void stopChildren() {
        Iterator<DefaultChannelOperations> it = children.iterator();
        while (it.hasNext()) {
            DefaultChannelOperations child = it.next();
            it.remove();
            child.orderlyCloseSafe();
        }
    }
    
    @Override
    public HttpServer add(Route route) {
        getRouteRegistry().add(route);
        return this;
    }
    
    @Override
    public Route remove(String pattern) {
        return getRouteRegistry().remove(pattern);
    }
    
    @Override
    public boolean remove(Route route) {
        return getRouteRegistry().remove(route);
    }
    
    RouteRegistry getRouteRegistry() {
        return registry;
    }
    
    @Override
    public Config getConfig() {
        return config;
    }
    
    @Override
    public InetSocketAddress getLocalAddress() throws IllegalStateException, IOException {
        try {
            return (InetSocketAddress) parent.get().join().getLocalAddress();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) { // including NPE from get().join()
            assert !(e instanceof ClassCastException);
            throw new IllegalStateException("Server is not running.", e);
        }
    }
    
    /**
     * Returns an unmodifiable {@code RandomAccess} {@code List} of error
     * handlers.
     * 
     * @return error handlers
     */
    List<ErrorHandler> getErrorHandlers() {
        return eh;
    }
    
    private class OnAccept implements CompletionHandler<AsynchronousSocketChannel, Void>
    {
        private static final String NO_MORE = " Will accept no more children.";
        
        private final AsynchronousServerSocketChannel parent;
        
        OnAccept(AsynchronousServerSocketChannel parent) {
            this.parent = parent;
        }
        
        @Override
        public void completed(AsynchronousSocketChannel child, Void noAttachment) {
            try {
                parent.accept(null, this);
            } catch (Throwable t) {
                new DefaultChannelOperations(child).orderlyCloseSafe();
                failed(t, null);
                return;
            }
            setup(child);
        }
        
        private void setup(AsynchronousSocketChannel child) {
            LOG.log(INFO, () -> "Accepted child: " + child);
            DefaultChannelOperations dco = new DefaultChannelOperations(child);
            ChannelByteBufferPublisher bytes = new ChannelByteBufferPublisher(dco);
            children.add(dco);
            startExchange(dco, bytes);
            
            // TODO: child.setOption(StandardSocketOptions.SO_KEEPALIVE, true); ??
        }
        
        private void startExchange(DefaultChannelOperations dco, ChannelByteBufferPublisher bytes) {
            new HttpExchange(DefaultServer.this, dco, bytes)
                    .begin()
                    .whenComplete((Null, exc) -> {
                        // Both open-calls are volatile reads, no locks
                        if (exc != null || !parent.isOpen() || !dco.isEverythingOpen()) {
                            shutdown(dco, bytes);
                        } else {
                            startExchange(dco, bytes);
                        }
                    });
        }
        
        private void shutdown(DefaultChannelOperations dco, ChannelByteBufferPublisher bytes) {
            bytes.close();
            dco.orderlyCloseSafe();
            children.remove(dco);
            // Notify anyone waiting on the last child
            CompletableFuture<Void> trigger = waitOnChildrenStop.get();
            if (trigger != null && children.isEmpty()) {
                trigger.complete(null);
            }
        }
        
        @Override
        public void failed(Throwable t, Void noAttachment) {
            if (t instanceof ClosedChannelException) { // note: AsynchronousCloseException extends ClosedChannelException
                LOG.log(DEBUG, "Parent channel closed." + NO_MORE);
            }
            else if (t instanceof ShutdownChannelGroupException) {
                LOG.log(DEBUG, "Group already closed when initiating a new accept." + NO_MORE);
            }
            else if (t instanceof IOException && t.getCause() instanceof ShutdownChannelGroupException) {
                LOG.log(DEBUG, "Connection accepted and immediately closed, because group is shutting down/was shutdown." + NO_MORE);
            }
            else {
                LOG.log(ERROR, "Unexpected or unknown failure. Stopping server (without force-closing children).", t);
                stop();
            }
        }
    }
}