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
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.NetworkChannel;
import java.nio.channels.ShutdownChannelGroupException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static java.net.InetAddress.getLoopbackAddress;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.StreamSupport.stream;

/**
 * A fully asynchronous implementation of {@code Server}.<p>
 * 
 * This class uses {@link AsynchronousServerSocketChannel} which provides an
 * amazing performance on many operating systems, including Windows.<p>
 * 
 * @implNote
 * When the server starts, an asynchronous server channel is opened and bound to
 * a specified port. The server channel is also known as "listener", "master"
 * and "parent".<p>
 * 
 * When the server channel accepts a new client connection, the resulting
 * channel - also known as the "child" - is handled by {@link OnAccept}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Consider renaming to ProactiveServer.
public final class AsyncServer implements Server
{
    private static final System.Logger LOG = System.getLogger(AsyncServer.class.getPackageName());
    
    // Good info on async groups:
    // https://openjdk.java.net/projects/nio/resources/AsynchronousIo.html
    
    private static AsynchronousChannelGroup group;
    
    public static synchronized AsynchronousChannelGroup group() throws IOException {
        if (group == null) {
            // TODO: Motivate in docs why we're not using default-group, or otherwise use default-group
            //       And also expose the thread pool through API.
            group = AsynchronousChannelGroup.withFixedThreadPool(
                    // Same N of threads as default-group, except we're not cached, rather fixed
                    Runtime.getRuntime().availableProcessors(),
                    // Non-daemon threads
                    Executors.defaultThreadFactory());
        }
        
        return group;
    }
    
    private final RouteRegistry routes;
    private final ServerConfig config;
    private final List<Supplier<ExceptionHandler>> onError;
    private AsynchronousServerSocketChannel listener;
    
    public AsyncServer(RouteRegistry routes, ServerConfig config, Iterable<Supplier<ExceptionHandler>> onError) {
        this.routes   = requireNonNull(routes);
        this.config   = requireNonNull(config);
        
        // Collectors.toUnmodifiableList() does not document RandomAccess
        List<Supplier<ExceptionHandler>> l
                = stream(onError.spliterator(), false).collect(toCollection(ArrayList::new));
        
        this.onError  = unmodifiableList(l);
        this.listener = null;
    }
    
    @Override
    public synchronized NetworkChannel start(SocketAddress address) throws IOException {
        if (listener != null) {
            throw new IllegalStateException("Already started.");
        }
        
        SocketAddress use = address != null? address :
                new InetSocketAddress(getLoopbackAddress(), 0);
        
        listener = AsynchronousServerSocketChannel.open(group()).bind(use);
        LOG.log(INFO, () -> "Opened server channel: " + listener);
        
        listener.accept(null, new OnAccept());
        
        return listener;
    }
    
    @Override
    public synchronized void stop() {
        throw new UnsupportedOperationException();
        
        // First, "listener.close();" but then we will also need to manage the
        // channel group which is shared by all servers. Last member of the
        // group to stop should shutdown orderly the group as well. Then from
        // that point on we want any new server starts to initialize a new group.
        // This could become tricky if application is supposed to be able to
        // specify or directly lookup the group's thread pool since shutting
        // down a group also shuts down the thread pool!
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
    List<Supplier<ExceptionHandler>> exceptionHandlers() {
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
     * @param child channel to close
     */
    void orderlyShutdown(Channel child) {
        if (!child.isOpen()) {
            return;
        }
        
        try {
            child.close();
            LOG.log(INFO, () -> "Closed child: " + child);
        } catch (IOException e) {
            LOG.log(ERROR, "Failed to close client channel. Will initiate orderly shutdown.", e);
            stopOrElseJVMExit();
        }
    }
    
    private void stopOrElseJVMExit() {
        // TODO: Use stop() when we have that implemented
        try {
            listener.close();
            LOG.log(INFO, () -> "Closed server channel: " + listener);
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
                listener.accept(null, this);
            } catch (Throwable t) {
                failed(t, null);
            }
            
            // TODO: child.setOption(StandardSocketOptions.SO_KEEPALIVE, true); ??
            
            ChannelBytePublisher publisher = new ChannelBytePublisher(AsyncServer.this, child);
            publisher.begin();
            
            new HttpExchange(AsyncServer.this, child, publisher).begin();
        }
        
        @Override
        public void failed(Throwable t, Void noAttachment) {
            if (t instanceof ClosedChannelException) {
                LOG.log(WARNING, "Channel already closed when initiating a new accept. Will accept no more.");
            }
            else if (t instanceof ShutdownChannelGroupException) {
                LOG.log(WARNING, "Group already closed when initiating a new accept. Will accept no more.");
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