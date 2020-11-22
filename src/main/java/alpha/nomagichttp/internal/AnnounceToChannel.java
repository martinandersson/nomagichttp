package alpha.nomagichttp.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ShutdownChannelGroupException;
import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.lang.Long.MAX_VALUE;
import static java.lang.Math.addExact;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;

/**
 * A service used to {@link #announce(ByteBuffer) announce} the availability of
 * bytebuffer resources to a channel's read- or write operations.<p>
 * 
 * The first operation will commence whenever the first bytebuffer is announced.
 * New operations will be initiated for as long as bytebuffers are available
 * until the service is stopped.<p>
 * 
 * The service stops when {@link #stop()} is called. {@code stop()} may be
 * called implicitly by announcing the bytebuffer sentinel {@link #NO_MORE} or
 * closing the channel.<p>
 * 
 * The service also stops on all channel failures or applicable for read mode
 * only; whenever end-of-stream is reached.<p>
 * 
 * Read mode specifies an {@code onComplete} callback which is invoked with the
 * container bytebuffer after each operation completes. The buffer will have
 * already been flipped and is therefore ready to be consumed as-is. The last
 * bytebuffer to reach the callback may be the sentinel value {@link #EOS}.<p>
 * 
 * The {@link WhenDone#accept(AsynchronousSocketChannel, long, Throwable)
 * whenDone} callback executes exactly-once whenever the last pending operation
 * completes.<p>
 * 
 * Please note that the responsibility of this class is to manage a particular
 * type of channel <i>operations</i> (read or write) for as long as the service
 * remains running, not the channel's life cycle itself. In particular note; the
 * only two occasions when this class <i>closes</i> the channel is if a channel
 * operation completes exceptionally or end-of-stream is reached.<p>
 * 
 * This class is thread-safe and non-blocking.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class AnnounceToChannel
{
    @FunctionalInterface
    interface WhenDone  {
        /**
         * Called when the last operation completes.
         * 
         * @param channel
         *     the underlying channel (so that client code doesn't have to save the reference)
         * @param byteCount
         *     total number of bytes that was either read or written (capped at {@code Long.MAX_VALUE})
         * @param exc only non-null if there was a problem
         */
        void accept(AsynchronousSocketChannel channel, long byteCount, Throwable exc);
    }
    
    static AnnounceToChannel read(
            AsynchronousSocketChannel source,
            Consumer<? super ByteBuffer> onComplete,
            DefaultServer server,
            WhenDone whenDone)
    {
        return new AnnounceToChannel(Mode.READ, source, onComplete, server, whenDone);
    }
    
    static AnnounceToChannel write(
            AsynchronousSocketChannel destination,
            DefaultServer server,
            WhenDone whenDone)
    {
        return new AnnounceToChannel(Mode.WRITE, destination, null, server, whenDone);
    }
    
    /**
     * The service will self-{@link #stop()} as soon as this sentinel bytebuffer
     * is announced and make its way to an initiating operation.
     */
    static final ByteBuffer NO_MORE = ByteBuffer.allocate(0);
    
    /**
     * Applicable for read mode only: passed to the {@code onComplete} callback
     * when the channel has reached end-of-stream.
     */
    static final ByteBuffer EOS = ByteBuffer.allocate(0);
    
    private static final System.Logger LOG
            = System.getLogger(AnnounceToChannel.class.getPackageName());
    
    private static final Throwable RUNNING = new Throwable("RUNNING"),
                                   STOPPED = new Throwable("STOPPED");
    
    private enum Mode {
        READ, WRITE;
        
        @Override
        public String toString() {
            return name().charAt(0) + name().toLowerCase(ROOT).substring(1);
        }
    }
    
    private final Mode mode;
    private final AsynchronousSocketChannel channel;
    private final Consumer<? super ByteBuffer> onComplete;
    private final DefaultServer server;
    
    private final SeriallyRunnable operation;
    private final Handler handler;
    private final Queue<ByteBuffer> buffers;
    private final AtomicReference<Throwable> state;
    
    private WhenDone whenDone;
    private long byteCount;
    
    private AnnounceToChannel(
            Mode mode,
            AsynchronousSocketChannel channel,
            Consumer<? super ByteBuffer> onComplete,
            DefaultServer server,
            WhenDone whenDone)
    {
        this.mode       = requireNonNull(mode);
        this.channel    = requireNonNull(channel);
        this.onComplete = onComplete != null ? onComplete : ignored -> {};
        this.server     = requireNonNull(server);
        this.whenDone   = requireNonNull(whenDone);
        
        this.operation = new SeriallyRunnable(this::pollAndInitiate, true);
        this.handler   = new Handler();
        this.buffers   = mode == Mode.READ ?
                new ConcurrentLinkedQueue<>() : new ConcurrentLinkedDeque<>();
        
        this.byteCount = 0;
        this.state = new AtomicReference<>(RUNNING);
    }
    
    /**
     * Announce the availability of a bytebuffer free to use for a future
     * channel operation.<p>
     * 
     * If the current mode is {@code read}, then the bytebuffer will be cleared
     * (it's assumed that we may use all of it).<p>
     * 
     * Bytebuffers will be used up in the order they are announced.<p>
     * 
     * While the bytebuffer is not used it will be sitting in an unbounded
     * queue. It's therefore a good idea to cap or otherwise throttle how many
     * bytebuffers are announced.
     */
    void announce(ByteBuffer buf) {
        if (mode == Mode.READ) {
            buf.clear();
        }
        
        if (buf != NO_MORE && !buf.hasRemaining()) {
            throw new IllegalArgumentException("Buffer has no storage capacity available.");
        }
        
        buffers.add(buf);
        operation.run();
    }
    
    /**
     * Stop the service.<p>
     * 
     * This is considered a "normal" stop and if effective because a contending
     * thread didn't {@link #stop(Throwable)} stop using a throwable, the {@code
     * whenDone} callback will be executed without a throwable - even if a
     * pending asynchronous operation completes exceptionally.<p>
     * 
     * The effect is immediate if called from a read operation's {@code
     * onComplete} callback. Otherwise, due to the asynchronous nature of this
     * class, the effect may be delayed with at most one extra operation
     * initiated.<p>
     * 
     * Is NOP if already stopped.
     *
     * @return {@code true} if call had an effect, otherwise {@code false}
     */
    boolean stop() {
        return stop(null);
    }
    
    /**
     * Stop the service.<p>
     * 
     * Same as {@link #stop()}, except also offers a parameter that can be used
     * to pass an exception to the {@code whenDone} callback.<p>
     * 
     * The only reason why this method invocation wouldn't succeed and
     * consequently return {@code false}, is if the service has stopped
     * already.<p>
     * 
     * If this method returns {@code true}, then the specified exception is what
     * will be delivered to the {@code whenDone} callback. In addition, similar
     * to a Java try-with-resources statement; if an ongoing asynchronous
     * operation completes exceptionally, the channel-related error will be
     * added as <i>suppressed</i> but does not stop the original exception from
     * being propagated.
     * 
     * @param t a throwable
     * 
     * @return {@code true} if call had an effect (throwable scheduled for
     *         callback delivery), otherwise {@code false}
     */
    boolean stop(Throwable t) {
        final Throwable prev = state.getAndUpdate(v -> {
            if (v == RUNNING) {
                return t != null ? t : STOPPED;
            }
            
            return v;
        });
        
        if (prev == RUNNING) {
            // Ensure callback executes
            operation.run();
            return true;
        }
        
        return false;
    }
    
    private void pollAndInitiate() {
        if (state.get() != RUNNING) {
            buffers.clear();
            executeCallbackOnce();
            operation.complete();
            return;
        }
        
        final ByteBuffer b = buffers.poll();
        
        if (b == null) {
            operation.complete();
            return;
        } else if (b == NO_MORE || !channel.isOpen()) {
            stop();
            operation.complete();
            return;
        }
        
        try {
            switch (mode) {
                case READ:  channel.read( b, b, handler); break;
                case WRITE: channel.write(b, b, handler); break;
                default:    throw new UnsupportedOperationException("What is this?: " + mode);
            }
        } catch (Throwable t) {
            handler.failed(t, null);
        }
    }
    
    private void executeCallbackOnce() {
        final Throwable t1 = state.getAndSet(STOPPED);
        
        if (whenDone != null) {
            // Propagate null instead of sentinel
            final Throwable t2 = t1 == RUNNING || t1 == STOPPED ? null : t1;
            try {
                whenDone.accept(channel, byteCount, t2);
            } finally {
                // Ensure we don't execute callback again
                whenDone = null;
            }
        }
    }
    
    private final class Handler implements java.nio.channels.CompletionHandler<Integer, ByteBuffer>
    {
        @Override
        public void completed(Integer result, ByteBuffer buf) {
            final int r = result;
            
            if (r == -1) {
                LOG.log(DEBUG, "End of stream; other side must have closed.");
                // TODO: Not sure I want to close the entire channel here, perhaps only shutdown input if anything
                server.orderlyShutdown(channel); // <-- will cause stop() to be called next run
                buf = EOS;
            } else {
                try {
                    byteCount = addExact(byteCount, r);
                } catch (ArithmeticException e) {
                    byteCount = MAX_VALUE;
                }
            }
            
            if (mode == Mode.READ) {
                buf.flip();
            } else if (buf.hasRemaining()) {
                LOG.log(DEBUG, () -> mode + " operation didn't read all of the buffer, adding back as head of queue.");
                ((Deque<ByteBuffer>) buffers).addFirst(buf);
            }
            
            onComplete.accept(buf);
            operation.run(); // <-- schedule new run; perhaps more work or execute whenDone callback
            operation.complete();
        }
        
        @Override
        public void failed(Throwable exc, ByteBuffer ignored) {
            boolean loggedStack = false;
            
            if (!stop(exc)) {
                Throwable t = state.get();
                if (t == STOPPED) {
                    // Was "stopped normally", we only log ours - if need be
                    if (!failedBecauseChannelWasAlreadyClosed(exc)) {
                        loggedStack = true;
                        LOG.log(ERROR, () ->
                            mode + " operation failed and service already stopped normally; " +
                            "can not propagate the error anywhere.", exc);
                    } // else channel was effectively closed AND service stopped already, really not a problem then
                } else {
                    // Someone else has scheduled a different error to propagate
                    t.addSuppressed(exc);
                }
            }
            
            if (channel.isOpen()) {
                if (loggedStack) {
                    LOG.log(ERROR, "Will close the channel.");
                } else {
                    LOG.log(ERROR, () -> mode + " operation failed and channel is still open, will close it.", exc);
                }
                server.orderlyShutdown(channel);
            } // else assume reason for closing has been logged already
            
            operation.run();
            operation.complete();
        }
    }
    
    private static boolean failedBecauseChannelWasAlreadyClosed(Throwable t) {
        // Copy-paste from DefaultServer class (has the descriptions as well)
        return t instanceof ClosedChannelException || // note: AsynchronousCloseException extends ClosedChannelException
               t instanceof ShutdownChannelGroupException ||
               t instanceof IOException && t.getCause() instanceof ShutdownChannelGroupException;
    }
}