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
import java.nio.channels.NetworkChannel;
import java.util.concurrent.Executors;

import static java.net.InetAddress.getLoopbackAddress;
import static java.util.Objects.requireNonNull;

/**
 * A fully asynchronous implementation of {@code Server}.<p>
 * 
 * This class uses {@link AsynchronousServerSocketChannel} which provides an
 * amazing performance on many operating systems, including Windows.<p>
 * 
 * When the server starts, an asynchronous server channel is opened and bound to
 * a specified port. The server channel is also known as "listener", "master"
 * and even "parent".<p>
 * 
 * When the server channel accepts a new client connection, the resulting
 * channel - also known as the "child" - is handled by {@link OnAccept}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class AsyncServer implements Server
{
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
    private final ExceptionHandler onError;
    private AsynchronousServerSocketChannel listener;
    
    public AsyncServer(RouteRegistry routes, ServerConfig config, ExceptionHandler onError) {
        this.routes   = requireNonNull(routes);
        this.config   = requireNonNull(config);
        this.onError  = requireNonNull(onError);
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
        new OnAccept(listener, onError, this);
        
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
}