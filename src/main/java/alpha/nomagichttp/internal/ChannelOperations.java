package alpha.nomagichttp.internal;

import java.io.IOException;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.util.Objects.requireNonNull;

/**
 * An extension API for {@code AsynchronousSocketChannel}, presumably the
 * child.<p>
 * 
 * This class is thread-safe but may be blocking due to the Java APIs used.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ChannelOperations
{
    private static final System.Logger LOG
            = System.getLogger(ChannelOperations.class.getPackageName());
    
    private final AsynchronousSocketChannel ch;
    private final DefaultServer server;
    
    private volatile boolean readShutdown,
                             writeShutdown;
    
    ChannelOperations(AsynchronousSocketChannel delegate, DefaultServer server) {
        this.ch = requireNonNull(delegate);
        this.server = requireNonNull(server);
        readShutdown = writeShutdown = false;
    }
    
    /**
     * Returns the underlying channel this API delegates to.
     * 
     * @return the underlying channel this API delegates to
     */
    AsynchronousSocketChannel delegate() {
        return ch;
    }
    
    /**
     * Shutdown the child connection's input stream.<p>
     * 
     * If this operation fails or effectively terminates the connection (output
     * stream also shutdown), then this method propagates to {@link
     * #orderlyClose()}.<p>
     * 
     * Note: Reason for shutting down should first be logged.<p>
     * 
     * Is NOP if input already shutdown or channel is closed.
     */
    void orderlyShutdownInput() {
        if (readShutdown) {
            return;
        }
        
        try {
            ch.shutdownInput();
            readShutdown = true;
        } catch (ClosedChannelException e) {
            readShutdown = true;
            return;
        } catch (Throwable t) {
            LOG.log(ERROR,
                "Failed to shutdown child connection's input stream. " +
                "Will close channel (reduce security risk).", t);
            orderlyClose();
            return;
        }
        
        if (writeShutdown) {
            orderlyClose();
        }
    }
    
    /**
     * Shutdown the child connection's output stream.<p>
     *
     * If this operation fails or effectively terminates the connection (input
     * stream also shutdown), then this method propagates to {@link
     * #orderlyClose()}.<p>
     *
     * Note: Reason for shutting down should first be logged.<p>
     *
     * Is NOP if output already shutdown or channel is closed.
     */
    void orderlyShutdownOutput() {
        if (writeShutdown) {
            return;
        }
        
        try {
            ch.shutdownOutput();
            writeShutdown = true;
        } catch (ClosedChannelException e) {
            writeShutdown = true;
            return;
        } catch (Throwable t) {
            LOG.log(ERROR,
                "Failed to shutdown child connection's output stream. " +
                "Will close channel (reduce security risk).", t);
            orderlyClose();
            return;
        }
        
        if (readShutdown) {
            orderlyClose();
        }
    }
    
    /**
     * End the child channel's connection and then close the channel.<p>
     * 
     * In order to reduce the security risk of leaving open phantom channels
     * behind, the following sequential closing-procedure will take place which
     * progresses only if the previous step failed:
     * 
     * <ol>
     *   <li>Close the channel</li>
     *   <li>Close the server</li>
     *   <li>Exit the JVM</li>
     * </ol>
     * 
     * Is NOP if child is already closed.<p>
     * 
     * Note 1: Ending the connection is done on a best-effort basis; failures
     * are logged but otherwise ignored. We are only paranoid over not being
     * able to close the channel. As long as we manage to close it we're happy.
     * Lingering communication on a dead connection will eventually hit a
     * "broken pipe" error and is thus not a strong reason enough for us to go
     * and kill the entire server.<p>
     * 
     * Note 2: It's very much possible that our paranoia procedure must go away:
     * killing the server or the JVM is simply put <i>worse</i> than not being
     * able to close an individual channel. The real remedy could be as simple
     * as to log the error and otherwise ignore it. TODO: Research
     */
    void orderlyClose() {
        if (!ch.isOpen()) {
            return;
        }
        
        // https://stackoverflow.com/a/20749656/1268003
        try {
            ch.shutdownInput();
            readShutdown = true;
        } catch (Throwable t) {
            // Fine, other peer will eventually receive "broken pipe" error or whatever
            LOG.log(DEBUG, "Failed to shutdown child connection's input stream.", t);
        }
        
        try {
            ch.shutdownOutput();
            writeShutdown = true;
        } catch (Throwable t) {
            LOG.log(DEBUG, "Failed to shutdown child connection's output stream.", t);
        }
        
        try {
            ch.close();
            LOG.log(INFO, () -> "Closed child: " + ch);
        } catch (IOException e) {
            LOG.log(ERROR, "Failed to close child. Will stop server (reduce security risk).", e);
            server.stopOrElseJVMExit();
        }
    }
    
    /**
     * Returns {@code true} for as long as this API hasn't been used to
     * successfully call {@link AsynchronousSocketChannel#shutdownInput()},
     * otherwise {@code false}.<p>
     * 
     * Note: This method does not probe the current connection status. There's
     * no such support in the AsynchronousSocketChannel API. But as long as our
     * API is the only API used to end the connection (or close the channel)
     * then the returned boolean should tell the truth.
     * 
     * @return see javadoc
     */
    boolean isOpenForReading() {
        return !readShutdown;
    }
    
    /**
     * Returns {@code true} for as long as this API hasn't been used to
     * successfully call {@link AsynchronousSocketChannel#shutdownOutput()},
     * otherwise {@code false}.<p>
     *
     * Note: This method does not probe the current connection status. There's
     * no such support in the AsynchronousSocketChannel API. But as long as our
     * API is the only API used to end the connection (or close the channel)
     * then the returned boolean should tell the truth.
     *
     * @return see javadoc
     */
    boolean isOpenForWriting() {
        return !writeShutdown;
    }
}