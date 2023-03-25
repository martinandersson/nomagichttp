package alpha.nomagichttp.internal;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.Attributes;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.Throwing;

import java.io.IOException;
import java.nio.channels.SocketChannel;

import static java.lang.System.Logger.Level.WARNING;

/**
 * Default implementation of {@code ClientChannel}.<p>
 * 
 * Implements shutdown/close methods and delegates the writer-api to
 * {@link ChannelWriter}.
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
     * Sets or replaces the backing writer.
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
    public boolean isInputOpen() {
        return !inputClosed;
    }
    
    @Override
    public boolean isOutputOpen() {
        return !outputClosed;
    }
    
    @Override
    public void shutdownOutput() {
        if (outputClosed) {
            return;
        }
        try {
            runSafe(child::shutdownOutput, "shutdownOutput");
        } finally {
            if (inputClosed) {
                close();
            }
            outputClosed = true;
        }
    }
    
    @Override
    public void shutdownInput() {
        if (inputClosed) {
            return;
        }
        try {
            runSafe(child::shutdownInput, "shutdownInput");
        } finally {
            if (outputClosed) {
                close();
            }
            inputClosed = true;
        }
    }
    
    @Override
    public void close() {
        if (inputClosed && outputClosed) {
            return;
        }
        inputClosed = outputClosed = true;
        runSafe(child::close, "close");
    }
    
    private void runSafe(Throwing.Runnable<IOException> r, String method) {
        try {
            r.run();
        } catch (IOException e) {
            LOG.log(WARNING, () ->
                    "Not propagating this exception; the " +
                    method + " method call is considered successful.", e);
        }
    }
    
    // WRITER
    // ------
    
    @Override
    public long write(Response response) throws IOException {
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
    
    @Override
    public void scheduleClose(String reason) {
        writer.scheduleClose(reason);
    }
}