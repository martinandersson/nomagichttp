package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.Response;

import java.io.IOException;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletionStage;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.util.Objects.requireNonNull;

final class DefaultClientChannel implements ClientChannel
{
    private static final System.Logger LOG
            = System.getLogger(DefaultClientChannel.class.getPackageName());
    
    private final AsynchronousSocketChannel child;
    private final ResponsePipeline pipe;
    private final Runnable onClose;
    
    private volatile boolean readShutdown,
                             writeShutdown;
    
    /**
     * Constructs a {@code DefaultClientChannel}.<p>
     * 
     * The HTTP version may be {@code null}, in which case this instance can not
     * be used for writing responses (no backing pipeline). This variant is
     * useful only for a select few internal server components as a means to
     * access the channel-state management API of this class. An
     * application-facing instance must have a HTTP version set.<p>
     * 
     * The on-channel-close callback is only called if the child channel is
     * closed through this API. If the channel reference is closed or the
     * channel is asynchronously closed then the callback is never invoked
     * (TODO: must implement timeouts). The callback may also be called multiple
     * times, even concurrently.
     * 
     * @param child of parent (client)
     * @param ver HTTP version (may be {@code null})
     * @param onChannelClose callback (may be {@code null})
     * 
     * @throws NullPointerException if {@code client} is {@code null}
     */
    DefaultClientChannel(
            AsynchronousSocketChannel child,
            HttpConstants.Version ver,
            Runnable onChannelClose)
    {
        this.child   = requireNonNull(child);
        this.pipe    = ver == null ? null : new ResponsePipeline(this, ver);
        readShutdown = writeShutdown = false;
        this.onClose = onChannelClose;
    }
    
    @Override
    public AsynchronousSocketChannel delegate() {
        return child;
    }
    
    ResponsePipeline pipeline() {
        if (pipe == null) {
            throw new UnsupportedOperationException("Need HTTP version.");
        }
        return pipe;
    }
    
    @Override
    public void write(Response resp) {
        write(resp.completedStage());
    }
    
    @Override
    public void write(CompletionStage<Response> response) {
        pipe.add(response);
    }
    
    @Override
    public boolean isOpenForReading() {
        if (!readShutdown) {
            // We think reading is open but doesn't hurt probing a little bit more:
            return child.isOpen();
        }
        return false;
    }
    
    @Override
    public boolean isOpenForWriting() {
        if (!writeShutdown) {
            return child.isOpen();
        }
        return false;
    }
    
    @Override
    public boolean isEverythingOpen() {
        return !readShutdown && !writeShutdown && child.isOpen();
    }
    
    @Override
    public void shutdownInput() throws IOException {
        if (readShutdown) {
            return;
        }
        
        try {
            child.shutdownInput();
            readShutdown = true;
        } catch (ClosedChannelException e) {
            readShutdown = true;
            return;
        } catch (IOException t) {
            LOG.log(ERROR, "Failed to shutdown child channel's input stream.", t);
        }
        
        if (writeShutdown) {
            close();
        }
    }
    
    @Override
    public void shutdownInputSafe() {
        try {
            shutdownInput();
        } catch (IOException e) {
            LOG.log(ERROR, () -> "Failed to close child: " + child, e);
        }
    }
    
    @Override
    public void shutdownOutput() throws IOException {
        if (writeShutdown) {
            return;
        }
        
        try {
            child.shutdownOutput();
            writeShutdown = true;
        } catch (ClosedChannelException e) {
            writeShutdown = true;
            return;
        } catch (IOException t) {
            LOG.log(ERROR, "Failed to shutdown child channel's output stream.", t);
        }
        
        if (readShutdown) {
            close();
        }
    }
    
    @Override
    public void shutdownOutputSafe() {
        try {
            shutdownOutput();
        } catch (IOException e) {
            LOG.log(ERROR, () -> "Failed to close child: " + child, e);
        }
    }
    
    @Override
    public void close() throws IOException {
        if (!child.isOpen()) {
            return;
        }
        
        // https://stackoverflow.com/a/20749656/1268003
        try {
            child.shutdownInput();
            readShutdown = true;
        } catch (IOException t) {
            LOG.log(DEBUG, "Failed to shutdown child channel's input stream.", t);
        }
        
        try {
            child.shutdownOutput();
            writeShutdown = true;
        } catch (IOException t) {
            LOG.log(DEBUG, "Failed to shutdown child channel's output stream.", t);
        }
        
        child.close();
        LOG.log(DEBUG, () -> "Closed child: " + child);
        if (onClose != null) {
            onClose.run();
        }
    }
    
    @Override
    public void closeSafe() {
        try {
            close();
        } catch (IOException e) {
            LOG.log(ERROR, () -> "Failed to close child: " + child, e);
        }
    }
}