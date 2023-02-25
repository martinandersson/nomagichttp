package alpha.nomagichttp.handler;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.Config;
import alpha.nomagichttp.message.AttributeHolder;

import java.io.IOException;

/**
 * An API for operating the client channel.<p>
 * 
 * The life-cycle of the channel is managed by the server. The application
 * should have no need to shut down any of its streams or close the channel
 * explicitly, which would translate to an abrupt end of the request and/or
 * response in-flight.<p>
 * 
 * For a graceful close of the client connection — which allows an in-flight
 * exchange to complete — set the "Connection: close" header in a response. This
 * header is tracked/memorized and so once observed by the channel
 * implementation, the effect will not roll back.<p>
 * 
 * TODO: This paragraph is legacy. We need to implement v-thread timeouts.<br>
 * The application may desire to {@link #shutdownInput()}, for example to
 * explicitly abort an exchange, or to stop the {@linkplain Config#timeoutRead()
 * read timeout} when sending long-lasting streams.<p>
 * 
 * None of the shutdown/close methods in this class throws {@link IOException},
 * although the underlying channel do. It is assumed (perhaps wrongfully?
 * *scratching head*) that there is nothing the application can or would like to
 * do about the exception, nor would the exception mean that the operation
 * wasn't successful (if the resource can't even close then it's pretty much
 * dead as dead can be), hence the exception is logged on the {@code WARNING}
 * level and then ignored.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Remove AttributeHolder
public interface ClientChannel extends ChannelWriter, AttributeHolder
{
    /**
     * Returns {@code true} if the input stream is open.<p>
     * 
     * This method does not probe the channel's actual status. It simply reads a
     * local flag set by the {@link #shutdownInput()} and {@link #close()}
     * methods.
     * 
     * @return see JavaDoc
     */
    boolean isInputOpen();
    
    /**
     * Returns {@code true} if the output stream is open.<p>
     * 
     * This method does not probe the channel's actual status. It simply reads a
     * local flag set by the {@link #shutdownOutput()} and {@link #close()}
     * methods.
     * 
     * @return see JavaDoc
     */
    boolean isOutputOpen();
    
    /**
     * Shutdown the input stream.<p>
     * 
     * If this operation fails or effectively terminates the connection (output
     * stream was also shutdown), then this method cascades to
     * {@link #close()}.<p>
     * 
     * Is NOP if input already shutdown or channel is closed.
     */
    void shutdownInput();
    
    /**
     * Shutdown the output stream.<p>
     * 
     * If this operation fails or effectively terminates the connection (input
     * stream was also shutdown), then this method propagates to {@link
     * #close()}.<p>
     * 
     * Is NOP if output already shutdown or channel is closed.
     */
    void shutdownOutput();
    
    /**
     * Closes the channel.<p>
     * 
     * Invoking this method is equivalent to an instant "kill" of the HTTP
     * connection and any ongoing message exchange, or a "headshot", if you
     * prefer. Only gaming enthusiasts should be using this method.<p>
     * 
     * Is NOP if channel is already closed.
     * 
     * @see #shutdownInput()
     * @see #shutdownOutput()
     */
    void close();
}