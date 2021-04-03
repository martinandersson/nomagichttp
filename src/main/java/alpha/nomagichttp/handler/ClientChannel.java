package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.Response;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.NetworkChannel;
import java.util.concurrent.CompletionStage;

/**
 * A nexus of operations for querying the state of a client channel and
 * scheduling HTTP responses.<p>
 * 
 * Generally, a channel can not be used to write multiple responses. Only if a
 * previous response failed without writing any bytes on the wire will a write
 * method declared in this class accept a new response.<p>
 * 
 * {@code IOException}s from underlying Java APIs used by operations that
 * shutdown the connection (read and/or write streams) is logged but otherwise
 * ignored. Lingering communication on a dead connection will eventually hit a
 * "broken pipe" error.<p>
 * 
 * The implementation is thread-safe and mostly non-blocking. Underlying channel
 * life-cycle APIs used to query the state of a channel or close it may block
 * and if so, the block is minuscule.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// https://stackoverflow.com/a/61117435/1268003
public interface ClientChannel extends Closeable
{
    /**
     * Write a response.<p>
     * 
     * This method does not block.<p>
     * 
     * It is currently not defined what happens to exceptions thrown by the
     * given response.
     * 
     * @param response the response
     * 
     * @throws NullPointerException
     *             if {@code response} is {@code null}
     * 
     * @throws IllegalStateException
     *             if a response is already in-flight, or
     *             more than 0 bytes already written on channel during HTTP exchange
     */
    void write(Response response);
    
    /**
     * Write a response.<p>
     * 
     * This method does not block.<p>
     * 
     * If the given stage completes exceptionally, then the exception will pass
     * through the server's {@link ErrorHandler}.
     * 
     * @param response the response
     * 
     * @throws NullPointerException
     *             if {@code response} is {@code null}
     * 
     * @throws IllegalStateException
     *             if a response is already in-flight, or
     *             more than 0 bytes already written on channel during HTTP exchange
     */
    void write(CompletionStage<Response> response);
    
    /**
     * Returns {@code true} if the application may assume that the underlying
     * channel's input stream is open, otherwise {@code false}.<p>
     * 
     * Note: This method does not probe the current connection status. There's
     * no such support in the underlying channel's API ({@code
     * AsynchronousSocketChannel}). But as long as the {@code ClientChannel} API
     * is the only API used by the application to end the connection (or close
     * the channel) then the returned boolean should tell the truth.
     * 
     * @return see JavaDoc
     */
    boolean isOpenForReading();
    
    /**
     * Returns {@code true} if the application may assume that the underlying
     * channel's output stream is open, otherwise {@code false}.<p>
     * 
     * Note: This method does not probe the current connection status. There's
     * no such support in the underlying channel's API ({@code
     * AsynchronousSocketChannel}). But as long as the {@code ClientChannel} API
     * is the only API used by the application to end the connection (or close
     * the channel) then the returned boolean should tell the truth.
     * 
     * @return see JavaDoc
     */
    boolean isOpenForWriting();
    
    /**
     * Returns {@code true} if the connection's read and write streams are open,
     * as well as the channel itself.
     * 
     * @return see JavaDoc
     * 
     * @see #isOpenForReading() 
     * @see #isOpenForWriting()
     */
    boolean isEverythingOpen();
    
    /**
     * Shutdown the channel's input stream.<p>
     * 
     * If this operation fails or effectively terminates the connection (output
     * stream was also shutdown), then this method propagates to {@link
     * #close()}.<p>
     * 
     * Is NOP if input already shutdown or channel is closed.
     *
     * @throws IOException if a propagated channel-close failed
     */
    void shutdownInput() throws IOException;
    
    /**
     * Same as {@link #shutdownInput()}, except {@code IOException} is logged
     * but otherwise ignored.
     */
    void shutdownInputSafe();
    
    /**
     * Shutdown the channel's output stream.<p>
     * 
     * If this operation fails or effectively terminates the connection (input
     * stream was also shutdown), then this method propagates to {@link
     * #close()}.<p>
     * 
     * Is NOP if output already shutdown or channel is closed.
     * 
     * @throws IOException if a propagated channel-close failed
     */
    void shutdownOutput() throws IOException;
    
    /**
     * Same as {@link #shutdownOutput()}, except {@code IOException} is logged
     * but otherwise ignored.
     */
    void shutdownOutputSafe();
    
    /**
     * End the channel's connection and then close the channel.<p>
     * 
     * Is NOP if channel is already closed.
     *
     * @throws IOException if closing the channel failed
     */
    @Override
    void close() throws IOException;
    
    /**
     * Same as {@link #close()}, except {@code IOException} is logged but
     * otherwise ignored.
     */
    void closeSafe();
    
    /**
     * Returns the underlying Java channel instance.
     * 
     * @return the underlying Java channel instance (never {@code null})
     */
    NetworkChannel delegate();
}