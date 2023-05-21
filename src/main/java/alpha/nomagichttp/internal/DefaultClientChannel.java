package alpha.nomagichttp.internal;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.Attributes;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.Throwing;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeoutException;

import static java.lang.System.Logger.Level.WARNING;

/**
 * Default implementation of {@code ClientChannel}.<p>
 * 
 * Implements shutdown/close methods and delegates the writer-api to
 * {@link ChannelWriter}.<p>
 * 
 * For life cycle details, see {@link #use(ChannelWriter)}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultClientChannel implements ClientChannel
{
    private static final System.Logger LOG
            = System.getLogger(DefaultClientChannel.class.getPackageName());
    
    private final SocketChannel child;
    private final Attributes attr;
    private ChannelWriter writer;
    private boolean inputClosed,
                    outputClosed;
    
    /**
     * Constructs this object.
     * 
     * @param child channel
     */
    DefaultClientChannel(SocketChannel child) {
        this.child = child;
        this.attr  = new DefaultAttributes();
    }
    
    /**
     * Sets or replaces the backing writer.<p>
     * 
     * Only one {@code ClientChannel} is created per child/connection, and
     * possibly re-used across many HTTP exchanges. The client channel is merely
     * an API on top of the underlying child, and also contains attributes which
     * therefore span across multiple exchanges.<p>
     * 
     * However, the life of the backing writer implementation is bound to only
     * one exchange. For example, to ensure that no responses can be sent after
     * the final response. That is why this method is used to set/replace the
     * writer instance at the start of each new exchange.
     * 
     * @param writer to be used as backing write implementation
     */
    void use(ChannelWriter writer) {
        this.writer = writer;
    }
    
    @Override
    public Attributes attributes() {
        return attr;
    }
    
    // SHUTDOWN/CLOSE
    // --------------
    
    @Override
    public void shutdownInput() {
        if (inputClosed) {
            return;
        }
        inputClosed = true;
        runSafe(child::shutdownInput, "shutdownInput");
    }
    
    @Override
    public void shutdownOutput() {
        if (outputClosed) {
            return;
        }
        outputClosed = true;
        runSafe(child::shutdownOutput, "shutdownOutput");
    }
    
    @Override
    public void close() {
        if (inputClosed && outputClosed) {
            return;
        }
        inputClosed = outputClosed = true;
        runSafe(child::close, "close");
    }
    
    private static void runSafe(Throwing.Runnable<IOException> op, String method) {
        try {
            op.run();
        } catch (ClosedChannelException e) {
            // Great, job done!
        } catch (IOException e) {
            LOG.log(WARNING, () ->
                    "Not propagating this exception; the " +
                    method + " operation is considered successful.", e);
        }
    }
    
    @Override
    public boolean isInputOpen() {
        return !inputClosed;
    }
    
    @Override
    public boolean isOutputOpen() {
        return !outputClosed;
    }
    
    @Override
    public boolean isAnyStreamOpen() {
        return isInputOpen() || isOutputOpen();
    }
    
    @Override
    public boolean areBothStreamsOpen() {
        return isInputOpen() && isOutputOpen();
    }
    
    @Override
    public boolean isClosed() {
        return inputClosed && outputClosed;
    }
    
    // WRITER
    // ------
    
    @Override
    public long write(Response response)
            throws InterruptedException, TimeoutException, IOException {
        return writer.write(response);
    }
    @Override
    public boolean wroteFinal() {
        return writer.wroteFinal();
    }
    
    @Override
    public long byteCount() {
        return writer.byteCount();
    }
}