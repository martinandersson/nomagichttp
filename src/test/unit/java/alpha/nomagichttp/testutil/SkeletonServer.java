package alpha.nomagichttp.testutil;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.SocketChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import static java.net.InetAddress.getLoopbackAddress;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * A dual-sided facade of an {@code AsynchronousServerSocketChannel}.<p>
 * 
 * The test case first {@code start()} the server which will internally open and
 * bind a listening server channel. The test then calls {@code newClient()}
 * which will open and return a [client] socket channel connected to the
 * listening channel's port. The test then calls {@code accept()} which returns
 * the [server] listening channel's accepted socket channel. At this point, the
 * test will have access to both channels on both sides.<p>
 * 
 * Other than creating a client channel and leaking the server channel, this
 * <i>skeleton</i> class has no other logic. The purpose of this class is to be
 * able to test byte processors and other internal components that depends on
 * the child connection.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class SkeletonServer implements Closeable
{
    private AsynchronousServerSocketChannel listener;
    private int port;
    private final BlockingQueue<AsynchronousSocketChannel> accepted;
    private final AtomicReference<Throwable> acceptExc;
    
    /**
     * Constructs a {@code SkeletonServer}.
     */
    public SkeletonServer() {
        listener = null;
        port = 0;
        accepted = new LinkedBlockingQueue<>();
        acceptExc = new AtomicReference<>();
    }
    
    /**
     * Start the server.
     * 
     * @throws IOException if an I/O error occurs
     */
    public void start() throws IOException {
        listener = AsynchronousServerSocketChannel.open()
                .bind(new InetSocketAddress(getLoopbackAddress(), 0));
        
        port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
        
        listener.accept(null, new CompletionHandler<>() {
            @Override public void completed(AsynchronousSocketChannel result, Object attachment) {
                accepted.add(result);
                listener.accept(null, this);
            }
            
            @Override public void failed(Throwable exc, Object attachment) {
                acceptExc.set(exc);
            }
        });
    }
    
    /**
     * Open socket channel connected to the server's port.
     * 
     * @return the socket channel
     * @throws IOException if an I/O error occurs
     */
    public SocketChannel newClient() throws IOException {
        return SocketChannel.open(
                new InetSocketAddress(getLoopbackAddress(), port));
    }
    
    /**
     * Returns the last accepted socket channel. May block for up to 3 seconds.
     * 
     * @return the last accepted socket channel
     * 
     * @throws InterruptedException if blocked for longer than 3 seconds
     */
    public AsynchronousSocketChannel accept() throws InterruptedException {
        final AsynchronousSocketChannel ch;
        
        try {
            ch = accepted.poll(3, SECONDS);
        }
        catch (InterruptedException e) {
            tryThrowAcceptExc(e);
            throw e;
        }
        
        tryThrowAcceptExc(null);
        return ch;
    }
    
    private void tryThrowAcceptExc(Throwable suppressed) throws CompletionException {
        Throwable t = acceptExc.getAndSet(null);
        if (t != null) {
            if (suppressed != null) {
                t.addSuppressed(suppressed);
            }
            throw new CompletionException(t);
        }
    }
    
    @Override
    public void close() throws IOException {
        if (listener != null) {
            listener.close();
        }
    }
}