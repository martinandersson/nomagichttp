package alpha.nomagichttp.internal;

import alpha.nomagichttp.ExceptionHandler;
import alpha.nomagichttp.Server;
import alpha.nomagichttp.ServerConfig;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.net.InetAddress.getLoopbackAddress;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.StreamSupport.stream;

/**
 * A fully asynchronous implementation of {@code Server}.<p>
 * 
 * This class uses {@link AsynchronousServerSocketChannel} which provides an
 * amazing performance on many operating systems, including Windows.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class DefaultServer implements Server
{
    private static final System.Logger LOG = System.getLogger(DefaultServer.class.getPackageName());
    
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
    
    private final RouteRegistry routes;
    private final ServerConfig config;
    private final List<Supplier<ExceptionHandler>> onError;
    private AsynchronousServerSocketChannel listener;
    private int port;
    
    public <S extends Supplier<? extends ExceptionHandler>> DefaultServer(
            RouteRegistry routes, ServerConfig config, Iterable<S> onError)
    {
        this.routes = requireNonNull(routes);
        this.config = requireNonNull(config);
        
        // Collectors.toUnmodifiableList() does not document RandomAccess
        List<Supplier<ExceptionHandler>> l = stream(onError.spliterator(), false)
                .map(e -> {
                    @SuppressWarnings("unchecked")
                    Supplier<ExceptionHandler> eh = (Supplier<ExceptionHandler>) e;
                    return eh; })
                .collect(toCollection(ArrayList::new));
        
        this.onError  = unmodifiableList(l);
        this.listener = null;
    }
    
    @Override
    public synchronized Server start(SocketAddress address) throws IOException {
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
            port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
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
    
    @Override
    public synchronized int getPort() throws IllegalStateException {
        if (listener == null || !listener.isOpen()) {
            throw new IllegalStateException("Server is not running.");
        }
        
        return port;
    }
    
    @Override
    public RouteRegistry getRouteRegistry() {
        return routes;
    }
    
    @Override
    public ServerConfig getServerConfig() {
        return config;
    }
    
    /**
     * Returns an unmodifiable {@code List} of exception handlers which
     * implements {@code RandomAccess}
     * 
     * @return exception handlers
     */
    List<Supplier<ExceptionHandler>> getExceptionHandlers() {
        return onError;
    }
    
    /**
     * Initiate an orderly shutdown of the specified child.<p>
     * 
     * If the child is not open, this method is NOP.<p>
     * 
     * If the child is open, the following sequential procedure will take place
     * which progresses only if the previous step failed:
     * 
     * <ol>
     *   <li>Close the child</li>
     *   <li>Close the server</li>
     *   <li>Exit the JVM</li>
     * </ol>
     * 
     * Please note that although thread-safe, this method may block if invoked
     * concurrently. Try not to invoke concurrently.
     * 
     * @param child channel to close
     */
    void orderlyShutdown(AsynchronousSocketChannel child) {
        if (!child.isOpen()) {
            return;
        }
        
        try {
            // https://stackoverflow.com/a/20749656/1268003
            child.shutdownInput().shutdownOutput().close();
            LOG.log(INFO, () -> "Closed child: " + child);
        } catch (IOException e) {
            LOG.log(ERROR, "Failed to close client channel. Will initiate orderly shutdown.", e);
            stopOrElseJVMExit();
        }
    }
    
    private void stopOrElseJVMExit() {
        try {
            stop();
        } catch (Throwable t) {
            LOG.log(ERROR, "Failed to close server. Will exit application (reduce security risk).", t);
            System.exit(1);
        }
    }
    
    /**
     * Handles the completion of a listener accept operation.<p>
     * 
     * The way this class handles an accepted channel (the client connection) is
     * to setup an asynchronous and continuous flow of anticipated {@link
     * HttpExchange HTTP exchanges}.<p>
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
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
            
            new HttpExchange(DefaultServer.this, child).begin();
        }
        
        @Override
        public void failed(Throwable t, Void noAttachment) {
            if (t instanceof ClosedChannelException) { // note: AsynchronousCloseException extends ClosedChannelException
                LOG.log(DEBUG, "Channel closed. Will accept no more.");
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