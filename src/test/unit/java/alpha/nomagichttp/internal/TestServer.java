package alpha.nomagichttp.internal;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.SocketChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import static java.net.InetAddress.getLoopbackAddress;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * A dual-sided facade of an {@code AsynchronousServerSocketChannel}.<p>
 * 
 * The test first {@code start()} the server which will internally open and bind
 * a new listening server channel. The test then calls {@code newClient()} which
 * will open and return a socket channel connected to the server channel's port.
 * The test then calls {@code accept()} which returns the server's accepted
 * socket channel. At this point, the test will have access to both channels on
 * both sides.<p>
 * 
 * The purpose of this class is to be able to test byte processors which depends
 * on the child connection.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class TestServer implements Closeable
{
    private AsynchronousServerSocketChannel listener;
    private int port;
    private final BlockingQueue<AsynchronousSocketChannel> accepted;
    private final AtomicReference<Throwable> acceptExc;
    
    TestServer() {
        listener = null;
        port = 0;
        accepted = new LinkedBlockingQueue<>();
        acceptExc = new AtomicReference<>();
    }
    
    TestServer start() throws IOException {
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
        
        return this;
    }
    
    SocketChannel newClient() throws IOException {
        return SocketChannel.open(
                new InetSocketAddress(getLoopbackAddress(), port));
    }
    
    AsynchronousSocketChannel accept() throws Throwable {
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
    
    private void tryThrowAcceptExc(Throwable suppressed) throws Throwable {
        Throwable t = acceptExc.getAndSet(null);
        if (t != null) {
            if (suppressed != null) {
                t.addSuppressed(suppressed);
            }
            throw t;
        }
    }
    
    @Override
    public void close() throws IOException {
        if (listener != null) {
            listener.close();
        }
    }
}