package alpha.nomagichttp.internal;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.util.IOExceptions;
import alpha.nomagichttp.util.SeriallyRunnable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.InterruptedByTimeoutException;
import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static alpha.nomagichttp.internal.DefaultServer.becauseChannelOrGroupClosed;
import static java.lang.Long.MAX_VALUE;
import static java.lang.Math.addExact;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

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
 * closing the channel's stream in use.<p>
 * 
 * The service also stops on all channel failures or, applicable for read mode
 * only; whenever end-of-stream is reached.<p>
 * 
 * Read mode specifies an {@code onComplete} callback which is invoked with the
 * container bytebuffer after each operation completes. The buffer will have
 * already been flipped and is therefore ready to be consumed as-is. The last
 * bytebuffer to reach the callback may not be the last bytebuffer(s) announced
 * but may have been replaced with the sentinel value {@link #EOS}.<p>
 * 
 * The {@link WhenDone#accept(long, Throwable) whenDone} callback executes
 * exactly-once whenever the last pending operation completes.<p>
 * 
 * In write mode, the service will self-{@link #stop(Throwable)} with an {@link
 * InterruptedByTimeoutException} if the operation takes longer than the
 * configured timeout. Read operations never time out (by this class). See
 * {@link Config#timeoutIdleConnection}.<p>
 * 
 * Please note that the responsibility of this class is to manage a particular
 * type of channel <i>operations</i> (read or write) for as long as the service
 * remains running, not the channel's life cycle itself. The only two occasions
 * when this class shuts down the channel's stream in use is if a channel
 * operation completes exceptionally or end-of-stream is reached.<p>
 * 
 * This class is thread-safe and mostly non-blocking (closing stream may block).
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
         * @param byteCount
         *     total number of bytes that was either read or written (capped at
         *     {@code Long.MAX_VALUE})
         * @param exc only non-null if there was a problem
         */
        void accept(long byteCount, Throwable exc);
    }
    
    static AnnounceToChannel read(
            DefaultClientChannel source,
            Consumer<? super ByteBuffer> onComplete,
            WhenDone whenDone)
    {
        return new AnnounceToChannel(Mode.READ, source, onComplete, whenDone, null);
    }
    
    static AnnounceToChannel write(
            DefaultClientChannel destination,
            WhenDone whenDone,
            Duration timeout)
    {
        return new AnnounceToChannel(Mode.WRITE, destination, null, whenDone, timeout);
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
            return name().toLowerCase(ROOT);
        }
        
        public String capitalized() {
            return name().charAt(0) + toString().substring(1);
        }
    }
    
    private final Mode mode;
    private final DefaultClientChannel chApi;
    private final Consumer<? super ByteBuffer> onComplete;
    private final long timeoutNs;
    
    private final SeriallyRunnable operation;
    private final Handler handler;
    private final Deque<ByteBuffer> buffers;
    private final AtomicReference<Throwable> state;
    
    private WhenDone whenDone;
    private long byteCount;
    
    private AnnounceToChannel(
            Mode mode,
            DefaultClientChannel chApi,
            Consumer<? super ByteBuffer> onComplete,
            WhenDone whenDone,
            Duration timeout)
    {
        this.mode       = requireNonNull(mode);
        this.chApi      = requireNonNull(chApi);
        this.onComplete = onComplete != null ? onComplete : ignored -> {};
        this.whenDone   = requireNonNull(whenDone);
        // with nanos, no further unit conversions in JDK's ScheduledThreadPoolExecutor
        this.timeoutNs  = mode == Mode.READ ? -1 : timeout.toNanos();
        
        this.operation = new SeriallyRunnable(this::pollAndInitiate, true);
        this.handler   = new Handler();
        this.buffers   = new ConcurrentLinkedDeque<>();
        
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
     * will be delivered to the {@code whenDone} callback.<p>
     * 
     * Similar to a Java try-with-resources statement; if an ongoing
     * asynchronous operation completes exceptionally and the exception fails to
     * be marked for delivery to the {@code whenDone} callback, then the error
     * will be added as <i>suppressed</i> by the given error (or whichever other
     * error was marked for delivery). The asynchronous failure does not stop
     * the marked exception from being propagated.
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
    
    /**
     * First call {@link #stop(Throwable)} with the given exception, then
     * shutdown the stream in use, then return what {@code stop(Throwable)}
     * returned.<p>
     * 
     * An ongoing operation will be aborted with an {@link
     * AsynchronousCloseException}. If this methods returns {@code true}, then
     * the close exception will be added as suppressed by the given error.<p>
     * 
     * Note: The stream will always shutdown, no matter the boolean return value
     * from this method.
     * 
     * @param t a throwable
     * 
     * @return same as {@link #stop(Throwable)}
     */
    boolean stopNow(Throwable t) {
        boolean s = stop(t);
        if (isStreamOpen()) {
            LOG.log(DEBUG, () ->
                "Asked to shutdown channel's " + mode + " stream, will shutdown the stream.");
            shutdownStream();
        }
        return s;
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
        } else if (b == NO_MORE) {
            stop();
            operation.complete();
            return;
        }
        
        var ch = chApi.getDelegateNoProxy();
        try {
            switch (mode) {
                case READ:
                    ch.read(b, b, handler);
                    break;
                case WRITE:
                    ch.write(b, timeoutNs, NANOSECONDS, b, handler);
                    break;
                default:
                    throw new UnsupportedOperationException("What is this?: " + mode);
            }
        } catch (Throwable t) {
            handler.failed(t, null);
        }
    }
    
    private void executeCallbackOnce() {
        final Throwable prev = state.getAndSet(STOPPED);
        
        if (whenDone != null) {
            // Propagate null instead of sentinel
            final Throwable real = prev == RUNNING || prev == STOPPED ? null : prev;
            try {
                whenDone.accept(byteCount, real);
            } finally {
                // Ensure we don't execute callback again
                whenDone = null;
            }
        }
    }
    
    private boolean isStreamOpen() {
        return mode == Mode.READ ?
                chApi.isOpenForReading() :
                chApi.isOpenForWriting();
    }
    
    private void shutdownStream() {
        if (mode == Mode.READ) {
            chApi.shutdownInputSafe();
        } else {
            assert mode == Mode.WRITE;
            chApi.shutdownOutputSafe();
        }
    }
    
    private final class Handler implements java.nio.channels.CompletionHandler<Integer, ByteBuffer>
    {
        @Override
        public void completed(Integer result, ByteBuffer buf) {
            final int r = result;
            
            if (r == -1) {
                assert mode == Mode.READ;
                LOG.log(DEBUG, "End of stream. Will shut down channel's input stream.");
                shutdownStream();
                buffers.addFirst(NO_MORE); // <-- will cause stop() to be called next run
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
                assert mode == Mode.WRITE;
                LOG.log(DEBUG, () -> mode.capitalized() +
                    " operation didn't read all of the buffer, adding back as head of queue.");
                buffers.addFirst(buf);
            }
            
            onComplete.accept(buf);
            operation.run(); // <-- schedule new run; perhaps more work or execute whenDone callback
            operation.complete();
        }
        
        @Override
        public void failed(Throwable exc, ByteBuffer ignored) {
            final boolean pushed = stop(exc);
            boolean loggedStack = false;
            
            // If we manage to push the error to someone else, we need not bother
            if (!pushed) {
                Throwable t = state.get();
                if (t == STOPPED) {
                    // Service "stopped normally", we only log ours - if need be
                    if (!becauseChannelOrGroupClosed(exc)) {
                        loggedStack = true;
                        LOG.log(ERROR, () -> mode.capitalized() +
                            " operation failed and service already stopped normally; " +
                            "can not propagate this error anywhere.", exc);
                    } // else channel was effectively closed AND service stopped already, nothing to do
                } else {
                    // Someone else has scheduled a different error to propagate
                    t.addSuppressed(exc);
                }
            }
            
            // All errors will shutdown the stream, and we ought to always log WHY stream was shut down
            if (isStreamOpen()) {
                if (loggedStack) {
                    // Simply append to what was logged already
                    LOG.log(DEBUG, "Will shutdown connection's used stream.");
                } else if (isCausedByBrokenStream(exc)) {
                    // Log "broken pipe", but no stack dump
                    LOG.log(DEBUG, () -> mode.capitalized() +
                        " operation failed (broken pipe), will shutdown stream.");
                } else {
                    Supplier<String> msg = () -> mode.capitalized() +
                        " operation failed and stream is still open, will shut it down.";
                    if (pushed) {
                        // Only message
                        LOG.log(DEBUG, msg);
                    } else {
                        // Full stack trace
                        LOG.log(DEBUG, msg, exc);
                    }
                }
                shutdownStream();
            } // else assume reason for closing has been logged already
            
            operation.run();
            operation.complete();
        }
    }
    
    private boolean isCausedByBrokenStream(Throwable t) {
        if (!(t instanceof IOException)) {
            return false;
        }
        IOException io = (IOException) t;
        switch (mode) {
            case READ:  return IOExceptions.isCausedByBrokenInputStream(io);
            case WRITE: return IOExceptions.isCausedByBrokenOutputStream(io);
            default:    throw new AssertionError("What is this?: " + mode);
        }
    }
}