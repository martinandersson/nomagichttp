package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.Attributes;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NetworkChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.util.Objects.requireNonNull;

final class DefaultClientChannel implements ClientChannel
{
    private static final System.Logger LOG
            = System.getLogger(DefaultClientChannel.class.getPackageName());
    
    private final AsynchronousSocketChannel child;
    private final HttpServer server;
    private final List<Runnable> onClose;
    private final Attributes attr;
    private ResponsePipeline pipe;
    
    private volatile boolean readShutdown,
                            writeShutdown;
    
    /**
     * Constructs a {@code DefaultClientChannel}.<p>
     * 
     * The channel can not be used for writing responses before this instance
     * has been {@link #usePipeline(ResponsePipeline) initialized}.
     * 
     * @param child channel
     * @param server parent
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    DefaultClientChannel(AsynchronousSocketChannel child, HttpServer server) {
        this.child   = requireNonNull(child);
        this.server  = requireNonNull(server);
        this.onClose = new ArrayList<>(1);
        this.attr    = new DefaultAttributes();
        this.pipe    = null;
        readShutdown = writeShutdown = false;
    }
    
    /**
     * Schedule a callback to run on channel close.<p>
     * 
     * This operation is not thread-safe with no memory fencing. Should only be
     * called before the first HTTP exchange begins. The callback may be invoked
     * concurrently, even multiple times. The callback is only called if the
     * channel is closed through the API exposed by this class.
     * 
     * @param callback on channel close
     * @throws NullPointerException if {@code callback} is {@code null}
     */
    void onClose(Runnable callback) {
        onClose.add(requireNonNull(callback));
        if (!child.isOpen()) {
            callback.run();
        }
    }
    
    /**
     * Initialize this channel with a pipeline or replace an old.<p>
     * 
     * This operation will enable the response-writing API of the interface.
     * Until then, only the channel state-management API is useful.
     * 
     * @param pipe response pipeline
     */
    void usePipeline(ResponsePipeline pipe) {
        this.pipe = pipe;
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
    public void writeFirst(Response response) {
        writeFirst(response.completedStage());
    }
    
    @Override
    public void writeFirst(CompletionStage<Response> response) {
        pipe.addFirst(response);
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
    
    public boolean isAnythingOpen() {
        return !readShutdown || !writeShutdown || child.isOpen();
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
            shutdownInputImpl();
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
    
    private void shutdownInputImpl() throws IOException {
        child.shutdownInput();
        readShutdown = true;
        LOG.log(DEBUG, () -> "Shutdown input stream of child: " + child);
    }
    
    @Override
    public void shutdownOutput() throws IOException {
        if (writeShutdown) {
            return;
        }
        
        try {
            shutdownOutputImpl();
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
    
    private void shutdownOutputImpl() throws IOException {
        child.shutdownOutput();
        writeShutdown = true;
        LOG.log(DEBUG, () -> "Shutdown output stream of child: " + child);
    }
    
    @Override
    public void close() throws IOException {
        if (!child.isOpen()) {
            return;
        }
        
        // https://stackoverflow.com/a/20749656/1268003
        try {
            shutdownInputImpl();
        } catch (IOException t) {
            LOG.log(DEBUG, "Failed to shutdown child channel's input stream.", t);
        }
        
        try {
            shutdownOutputImpl();
        } catch (IOException t) {
            LOG.log(DEBUG, "Failed to shutdown child channel's output stream.", t);
        }
        
        child.close();
        LOG.log(DEBUG, () -> "Closed child: " + child);
        onClose.forEach(Runnable::run);
    }
    
    @Override
    public void closeSafe() {
        try {
            close();
        } catch (IOException e) {
            LOG.log(ERROR, () -> "Failed to close child: " + child, e);
        }
    }
    
    private NetworkChannel proxy;
    
    @Override
    public NetworkChannel getDelegate() {
        NetworkChannel p = proxy;
        if (p == null) {
            proxy = p = new ProxiedNetworkChannel(child);
        }
        return p;
    }
    
    /**
     * Returns the raw delegate, not wrapped in a proxy.<p>
     *
     * The purpose of the proxy is to capture close-calls, so that we can make
     * safe assumptions about the channel state as well as to run
     * server-side close-callbacks for resource cleanup.<p>
     *
     * This method may be used when the interface {@code NetworkChannel} is not
     * sufficient or as a performance optimization but only if the close method
     * is not invoked on the returned reference.
     *
     * @return non-proxied delegate
     */
    AsynchronousSocketChannel getDelegateNoProxy() {
        return child;
    }
    
    @Override
    public HttpServer getServer() {
        return server;
    }
    
    @Override
    public Attributes attributes() {
        return attr;
    }
    
    private final class ProxiedNetworkChannel implements NetworkChannel
    {
        private final AsynchronousSocketChannel d;
        
        ProxiedNetworkChannel(AsynchronousSocketChannel d) {
            this.d = d;
        }
        
        @Override
        public NetworkChannel bind(SocketAddress local) throws IOException {
            return d.bind(local);
        }
        
        @Override
        public SocketAddress getLocalAddress() throws IOException {
            return d.getLocalAddress();
        }
        
        @Override
        public <T> NetworkChannel setOption(SocketOption<T> name, T value) throws IOException {
            return d.setOption(name, value);
        }
        
        @Override
        public <T> T getOption(SocketOption<T> name) throws IOException {
            return d.getOption(name);
        }
        
        @Override
        public Set<SocketOption<?>> supportedOptions() {
            return d.supportedOptions();
        }
        
        @Override
        public boolean isOpen() {
            return d.isOpen();
        }
        
        @Override
        public void close() throws IOException {
            DefaultClientChannel.this.close();
        }
    }
}