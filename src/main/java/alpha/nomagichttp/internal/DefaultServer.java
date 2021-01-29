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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.net.InetAddress.getLoopbackAddress;
import static java.util.Objects.requireNonNull;

/**
 * A fully asynchronous implementation of {@code Server}.<p>
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
    
    // Good info on async groups:
    // https://openjdk.java.net/projects/nio/resources/AsynchronousIo.html
    
    /**
     * Global count of active servers.
     */
    private static final AtomicInteger SERVER_COUNT = new AtomicInteger();
    private static AsynchronousChannelGroup group;
    
    private static synchronized AsynchronousChannelGroup group(int nThreads) throws IOException {
        if (group == null) {
            group = AsynchronousChannelGroup.withFixedThreadPool(nThreads,
                    // Default-group uses daemon threads, we use non-daemon
                    Executors.defaultThreadFactory());
        }
        
        return group;
    }
    
    private static synchronized  void groupShutdown() {
        if (group == null || SERVER_COUNT.get() > 0) {
            return;
        }
        
        group.shutdown();
        group = null;
    }
    
    private final Config config;
    private final RouteRegistry registry;
    private final List<ErrorHandler> eh;
    private AsynchronousServerSocketChannel listener;
    private InetSocketAddress addr;
    
    public DefaultServer(
            Config config,
            RouteRegistry registry,
            ErrorHandler... eh)
    {
        this.config = requireNonNull(config);
        this.registry = requireNonNull(registry);
        this.eh  = List.of(eh);
        this.listener = null;
    }
    
    @Override
    public synchronized HttpServer start(SocketAddress address) throws IOException {
        if (listener != null) {
            throw new IllegalStateException("Already running.");
        }
        
        SocketAddress use = address != null? address :
                new InetSocketAddress(getLoopbackAddress(), 0);
        
        try {
            AsynchronousChannelGroup grp = group(config.threadPoolSize());
            listener = AsynchronousServerSocketChannel.open(grp).bind(use);
        } catch (Throwable t) {
            groupShutdown();
            throw t;
        }
        
        SERVER_COUNT.incrementAndGet();
        LOG.log(INFO, () -> "Opened server channel: " + listener);
        
        try {
            addr = ((InetSocketAddress) listener.getLocalAddress());
            listener.accept(null, new OnAccept());
        } catch (Throwable t) {
            stop();
            throw t;
        }
        
        return this;
    }
    
    @Override
    public synchronized void stop() throws IOException {
        if (listener == null) {
            return;
        }
        
        if (!listener.isOpen()) {
            LOG.log(DEBUG, "Asked to stop server but channel was not open.");
        } else {
            listener.close();
            LOG.log(INFO, () -> "Closed server channel: " + listener);
        }
        
        listener = null;
        
        if (SERVER_COUNT.decrementAndGet() == 0) {
            groupShutdown();
        }
    }
    
    void stopOrElseJVMExit() {
        try {
            stop();
        } catch (Throwable t) {
            LOG.log(ERROR, "Failed to close server. Will exit application (reduce security risk).", t);
            System.exit(1);
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
    public synchronized InetSocketAddress getLocalAddress() throws IllegalStateException {
        if (listener == null || !listener.isOpen()) {
            throw new IllegalStateException("Server is not running.");
        }
        
        return addr;
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
        @Override
        public void completed(AsynchronousSocketChannel child, Void noAttachment) {
            LOG.log(INFO, () -> "Accepted child: " + child);
            
            try {
                if (listener.isOpen()) {
                    listener.accept(null, this);
                }
            } catch (Throwable t) {
                failed(t, null);
            }
            
            // TODO: child.setOption(StandardSocketOptions.SO_KEEPALIVE, true); ??
            
            DefaultChannelOperations ops = new DefaultChannelOperations(child, DefaultServer.this);
            new HttpExchange(DefaultServer.this, ops).begin();
        }
        
        @Override
        public void failed(Throwable t, Void noAttachment) {
            if (t instanceof ClosedChannelException) { // note: AsynchronousCloseException extends ClosedChannelException
                LOG.log(DEBUG, "Listening channel aka parent closed. Will accept no more.");
            }
            else if (t instanceof ShutdownChannelGroupException) {
                LOG.log(DEBUG, "Group already closed when initiating a new accept. Will accept no more.");
            }
            else if (t instanceof IOException && t.getCause() instanceof ShutdownChannelGroupException) {
                LOG.log(DEBUG, "Connection accepted and immediately closed, because group is shutting down/was shutdown. Will accept no more.");
            }
            else {
                LOG.log(ERROR, "Unknown failure. Will initiate orderly shutdown.", t);
                stopOrElseJVMExit();
            }
        }
    }
}