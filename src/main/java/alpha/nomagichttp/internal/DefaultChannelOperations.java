package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Request;

import java.io.IOException;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.util.Objects.requireNonNull;

/**
 * An extension API for {@code AsynchronousSocketChannel}, presumably the
 * child.<p>
 * 
 * This class is thread-safe but may in part be blocking due to the Java APIs
 * used.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultChannelOperations implements Request.ChannelOperations
{
    private static final System.Logger LOG
            = System.getLogger(DefaultChannelOperations.class.getPackageName());
    
    private final AsynchronousServerSocketChannel parent;
    private final AsynchronousSocketChannel ch;
    
    private volatile boolean readShutdown,
                             writeShutdown;
    
    DefaultChannelOperations(AsynchronousServerSocketChannel parent, AsynchronousSocketChannel delegate) {
        this.parent = requireNonNull(parent);
        this.ch = requireNonNull(delegate);
        readShutdown = writeShutdown = false;
    }
    
    /**
     * Returns the underlying channel this API delegates to.
     * 
     * @return the underlying channel this API delegates to
     */
    @Override
    public AsynchronousSocketChannel get() {
        return ch;
    }
    
    AsynchronousServerSocketChannel parent() {
        return parent;
    }
    
    /**
     * Shutdown the channel's input stream.<p>
     * 
     * If this operation fails or effectively terminates the connection (output
     * stream also shutdown), then this method propagates to {@link
     * #orderlyClose()}.<p>
     * 
     * Note: Reason for shutting down should first be logged.<p>
     * 
     * Is NOP if input already shutdown or channel is closed.
     * 
     * @throws IOException if a propagated channel-close failed
     */
    void orderlyShutdownInput() throws IOException {
        if (readShutdown) {
            return;
        }
        
        try {
            ch.shutdownInput();
            readShutdown = true;
        } catch (ClosedChannelException e) {
            readShutdown = true;
            return;
        } catch (IOException t) {
            LOG.log(ERROR,
                "Failed to shutdown child channel's input stream. " +
                "Will close channel (reduce security risk).", t);
            orderlyClose();
            return;
        }
        
        if (writeShutdown) {
            orderlyClose();
        }
    }
    
    /**
     * Same as {@link #orderlyShutdownInput()}, except {@code IOException} is
     * logged and not thrown.
     */
    void orderlyShutdownInputSafe() {
        try {
            orderlyShutdownInput();
        } catch (IOException e) {
            LOG.log(ERROR, () -> "Failed to close input stream of child: " + ch, e);
        }
    }
    
    /**
     * Shutdown the channel's output stream.<p>
     * 
     * If this operation fails or effectively terminates the connection (input
     * stream also shutdown), then this method propagates to {@link
     * #orderlyClose()}.<p>
     * 
     * Note: Reason for shutting down should first be logged.<p>
     * 
     * Is NOP if output already shutdown or channel is closed.
     * 
     * @throws IOException if a propagated channel-close failed
     */
    void orderlyShutdownOutput() throws IOException {
        if (writeShutdown) {
            return;
        }
        
        try {
            ch.shutdownOutput();
            writeShutdown = true;
        } catch (ClosedChannelException e) {
            writeShutdown = true;
            return;
        } catch (IOException t) {
            LOG.log(ERROR,
                "Failed to shutdown child channel's output stream. " +
                "Will close channel (reduce security risk).", t);
            orderlyClose();
            return;
        }
        
        if (readShutdown) {
            orderlyClose();
        }
    }
    
    /**
     * Same as {@link #orderlyShutdownOutput()}, except {@code IOException} is
     * logged and not thrown.
     */
    void orderlyShutdownOutputSafe() {
        try {
            orderlyShutdownOutput();
        } catch (IOException e) {
            LOG.log(ERROR, () -> "Failed to close output stream of child: " + ch, e);
        }
    }
    
    /**
     * End the channel's connection and then close the channel.<p>
     * 
     * Is NOP if child is already closed.<p>
     * 
     * Ending the connection is done on a best-effort basis; IO-failures are
     * logged but otherwise ignored. Lingering communication on a dead
     * connection will eventually hit a "broken pipe" error.
     * 
     * @throws IOException if closing the child failed
     */
    void orderlyClose() throws IOException {
        if (!ch.isOpen()) {
            return;
        }
        
        // https://stackoverflow.com/a/20749656/1268003
        try {
            ch.shutdownInput();
            readShutdown = true;
        } catch (IOException t) {
            // Fine, other peer will eventually receive "broken pipe" error or whatever
            LOG.log(DEBUG, "Failed to shutdown child channel's input stream.", t);
        }
        
        try {
            ch.shutdownOutput();
            writeShutdown = true;
        } catch (IOException t) {
            LOG.log(DEBUG, "Failed to shutdown child channel's output stream.", t);
        }
        
        ch.close();
        LOG.log(DEBUG, () -> "Closed child: " + ch);
    }
    
    /**
     * Same as {@link #orderlyClose()}, except {@code IOException} is logged and
     * not thrown.
     */
    void orderlyCloseSafe() {
        try {
            orderlyClose();
        } catch (IOException e) {
            LOG.log(ERROR, () -> "Failed to close child: " + ch, e);
        }
    }
    
    /**
     * Returns {@code true} if we may assume that the underlying channel's input
     * stream is open, otherwise {@code false}.<p>
     * 
     * Note: This method does not probe the current connection status. There's
     * no such support in the AsynchronousSocketChannel API. But as long as our
     * API is the only API used to end the connection (or close the channel)
     * then the returned boolean should tell the truth.
     * 
     * @return see JavaDoc
     */
    @Override
    public boolean isOpenForReading() {
        if (!readShutdown) {
            // We think reading is open but doesn't hurt probing a little bit more:
            return ch.isOpen();
        }
        return false;
    }
    
    /**
     * Returns {@code true} if we may assume that the underlying channel's
     * output stream is open, otherwise {@code false}.<p>
     *
     * Note: This method does not probe the current connection status. There's
     * no such support in the AsynchronousSocketChannel API. But as long as our
     * API is the only API used to end the connection (or close the channel)
     * then the returned boolean should tell the truth.
     *
     * @return see JavaDoc
     */
    boolean isOpenForWriting() {
        if (!writeShutdown) {
            return ch.isOpen();
        }
        return false;
    }
    
    /**
     * Returns {@code true} the connection and the channel is open, otherwise
     * {@code false}.
     * 
     * @return {@code true} the connection and the channel is open,
     *         otherwise {@code false}
     */
    boolean isEverythingOpen() {
        return !readShutdown && !writeShutdown && ch.isOpen();
    }
}