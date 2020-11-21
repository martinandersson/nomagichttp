package alpha.nomagichttp.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ShutdownChannelGroupException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.util.Objects.requireNonNull;

/**
 * {@link #announce()} the availability of a {@code ByteBuffer} resource to a
 * channel read- or write operation.<p>
 * 
 * The channel is assumed to not support concurrent operations and so will be
 * operated serially.<p>
 * 
 * The supplier function (constructor argument) is executed serially and may
 * return {@code null}, which would indicate there's no bytebuffer available for
 * the channel at the moment (a future announcement is expected).<p>
 * 
 * The {@code whenDone} callback (constructor argument) is called exactly-once
 * whenever no more operations will be initiated (only after a pending operation
 * completes), either because 1) {@link #stop()} was called (stop() may also be
 * implicitly called using sentinel {@link #NO_MORE} or closing the channel), 2)
 * an operation completed exceptionally, or 3) in read mode only; end-of-stream
 * was reached.<p>
 * 
 * Please note that the responsibility of this class is the channel
 * <i>operation</i>, not the channel's life cycle itself. Over the coarse of the
 * channel's life cycle - as far as this class is concerned - many operations
 * can come and go. In particular note; the only two occasions when this class
 * <i>closes</i> the channel is if a channel operation completes exceptionally
 * or end-of-stream is reached.<p>
 * 
 * This class is non-blocking and thread-safe.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see #NO_MORE
 * @see #EOS
 */
final class AnnounceToChannel
{
    // TODO: Internalize queues and make announce(ByteBuffer)
    
    /**
     * Called when service is stopped and/or the last operation completes.<p>
     * 
     * The provided {@code byteCount} is capped at {@code Long.MAX_VALUE}.
     */
    @FunctionalInterface
    interface WhenDone  {
        void accept(AsynchronousByteChannel channel, long byteCount, Throwable exc);
    }
    
    static AnnounceToChannel read(
            AsynchronousByteChannel source, Supplier<? extends ByteBuffer> destinations,
            Consumer<? super ByteBuffer> completionHandler, WhenDone whenDone, DefaultServer server)
    {
        return new AnnounceToChannel(source, Mode.READ, destinations, completionHandler, whenDone, server);
    }
    
    static AnnounceToChannel write(
            Supplier<? extends ByteBuffer> sources, AsynchronousByteChannel destination,
            Consumer<? super ByteBuffer> completionHandler, WhenDone whenDone, DefaultServer server)
    {
        return new AnnounceToChannel(destination, Mode.WRITE, sources, completionHandler, whenDone, server);
    }
    
    /**
     * The service will self-{@link #stop()} as soon as this sentinel bytebuffer
     * is polled from the supplier.
     */
    static final ByteBuffer NO_MORE = ByteBuffer.allocate(0);
    
    /**
     * Is passed by the service to the completion handler only if in read mode
     * and whenever the channel has reached end-of-stream.
     */
    static final ByteBuffer EOS = ByteBuffer.allocate(0);
    
    private static final System.Logger LOG
            = System.getLogger(AnnounceToChannel.class.getPackageName());
    
    private static final Throwable RUNNING = new Throwable("RUNNING"),
                                   STOPPED = new Throwable("STOPPED");
    
    private enum Mode {
        READ, WRITE
    }
    
    private final AtomicReference<Throwable> state;
    private final AsynchronousByteChannel channel;
    private final Mode mode;
    private final Supplier<? extends ByteBuffer> supplier;
    private final Consumer<? super ByteBuffer> completionHandler;
    private final DefaultServer server;
    private final SeriallyRunnable operation;
    private final Handler handler;
    
    private WhenDone whenDone;
    private long byteCount;
    
    private AnnounceToChannel(
            AsynchronousByteChannel channel,
            Mode mode,
            Supplier<? extends ByteBuffer> supplier,
            Consumer<? super ByteBuffer> completionHandler,
            WhenDone whenDone,
            DefaultServer server)
    {
        this.channel = requireNonNull(channel);
        this.mode = requireNonNull(mode);
        this.supplier = requireNonNull(supplier);
        this.completionHandler = requireNonNull(completionHandler);
        this.server = requireNonNull(server);
        this.whenDone = requireNonNull(whenDone);
        this.operation = new SeriallyRunnable(this::pollAndInitiate, true);
        this.handler = new Handler();
        this.byteCount = 0;
        this.state = new AtomicReference<>(RUNNING);
    }
    
    /**
     * Announce the presumed availability of a bytebuffer free to use for the
     * next channel operation.<p>
     * 
     * The thread calling this method may be used to initiate the next channel
     * operation. But, the channel operation either completes immediately or is
     * asynchronous, so this method will not block.
     */
    void announce() {
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
     * At most one extra bytebuffer may be polled from the supplier and used to
     * initiate a new channel operation even after this method returns.<p>
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
            executeCallbackOnce();
            operation.complete(); // <-- not really necessary
            return;
        }
        
        final ByteBuffer b = supplier.get();
        
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
    
    private final class Handler implements CompletionHandler<Integer, ByteBuffer>
    {
        @Override
        public void completed(Integer result, ByteBuffer buf) {
            final int r = result;
            
            if (r == -1) {
                LOG.log(DEBUG, "End of stream; other side must have closed.");
                server.orderlyShutdown(channel); // <-- will cause stop() to be called next run
                buf = EOS;
            } else {
                try {
                    byteCount = Math.addExact(byteCount, r);
                } catch (ArithmeticException e) {
                    byteCount = Long.MAX_VALUE;
                }
            }
            
            completionHandler.accept(buf);
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
                            "Unknown channel failure and service already stopped normally; " +
                            "can not propagate the error anywhere.", exc);
                    } // else channel was effectively closed AND service stopped already, really not a problem then
                } else {
                    // Someone else has scheduled a different error to propagate
                    t.addSuppressed(exc);
                }
            }
            
            if (channel.isOpen()) {
                final String msg = "Channel operation failed and channel is still open, will close it.";
                if (loggedStack) {
                    LOG.log(ERROR, msg);
                } else {
                    LOG.log(ERROR, msg, exc);
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