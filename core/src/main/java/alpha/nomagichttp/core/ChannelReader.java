package alpha.nomagichttp.core;

import alpha.nomagichttp.handler.EndOfStreamException;
import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.Request;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.NoSuchElementException;

import static alpha.nomagichttp.core.VThreads.CHANNEL_BLOCKING;
import static alpha.nomagichttp.core.VThreads.requireVirtualThread;
import static alpha.nomagichttp.util.Blah.addExactOrCap;
import static alpha.nomagichttp.util.Blah.toIntOrMaxValue;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static java.lang.Math.min;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.getLogger;
import static java.nio.ByteBuffer.allocate;
import static java.nio.ByteBuffer.allocateDirect;
import static java.util.Objects.requireNonNull;

/**
 * Is an iterable reader of bytes from a channel.<p>
 * 
 * The characteristics of this class are covered well by the JavaDoc of
 * {@link Request.Body}, who is just one of many that will over time iterate
 * bytes from this reader (i.e., the underlying child channel). Prior to the
 * request body, there will be two parsers respectively for the request line and
 * headers. Following the body there may be a parser of request trailers.<p>
 * 
 * Parsers and body decoders ought to know when they have reached their
 * respective delimiter. For a body of a known length, the method
 * {@link #limit(long) limit} must be used to cap the reader.<p>
 * 
 * The reader must be invalidated at the end of the HTTP exchange by calling the
 * method {@link #dismiss() dismiss}. This class self-dismisses on channel
 * failure and on an unexpected end-of-stream.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Must ensure we have a "echo large body" test,
//       i.e. reader and writer active at the same time
public final class ChannelReader implements ByteBufferIterable
{
    private static final System.Logger
            LOG = getLogger(ChannelReader.class.getPackageName());
    
    private static final int
            // Same as BufferedOutputStream/JEP-435
            BUFFER_SIZE = 512,
            // Sentinel; no limit has been set
            UNLIMITED = -1,
            // Sentinel; causes next() to throw IllegalStateExc
            DISMISSED = -2;
    
    // Sentinel; end-of-stream
    private static final ByteBuffer EOS = allocate(0);
    
    private final ReadableByteChannel src;
    private final ByteBuffer dst;
    // Not final because can be switched to EOS
    private       ByteBuffer view;
    private boolean started;
    private volatile boolean startedVol;
    private IOException thr;
    private final ByteBufferIterator it;
    // Number of bytes remaining to be read from the upstream
    private long desire;
    private final IdleConnTimeout timeout;
    
    /**
     * Constructs this object.<p>
     * 
     * Only the reader created for a channel's first HTTP exchange may be
     * created using this constructor. Successors must be derived from the
     * predecessor using {@link #newReader()}.
     * 
     * @param upstream to read bytes from
     * @param timeout for querying the timeout duration of an idle connection
     */
    ChannelReader(ReadableByteChannel upstream, IdleConnTimeout timeout) {
        // The buffers are created with no remaining to force a channel read
        
        // A direct buffer "typically" has higher allocation and de-allocation
        // cost [than heap] according to JDK JavaDoc. Out buffer is reused
        // throughout the channel's life, and hopefully will be used to serve
        // many exchanges. So this author is not necessarily concerned with the
        // [de-]allocation cost. However:
        // TODO: Need to throw some braincells at this, and profile.
        //       Maybe it would be wise to start on heap and switch to direct
        //       only when there's a large body - or, something? Content type
        //       could also be an indicator (most requests are likely to be
        //       text, and so, heap?).
        this(requireNonNull(upstream), allocateDirect(BUFFER_SIZE).position(BUFFER_SIZE),
             null, requireNonNull(timeout));
    }
    
    private ChannelReader(
            ReadableByteChannel src,
            ByteBuffer dst, ByteBuffer view,
            IdleConnTimeout timeout)
    {
        this.src    = src;
        this.dst    = dst;
        this.view   = view != null ? view : dst.asReadOnlyBuffer();
        this.it     = new IteratorImpl();
        this.desire = UNLIMITED;
        this.timeout = timeout;
    }
    
    /**
     * {@return the exception that caused a read operation to fail}<p>
     * 
     * This method returns {@code null} if no read operation has failed.
     */
    IOException getThrown() {
        return thr;
    }
    
    /**
     * {@return {@code true} if no read operation has completed}<p>
     * 
     * If this method returns {@code true}, then the connection is considered
     * idling, and it is safe to close the child during a graceful shutdown of
     * the server.<p>
     * 
     * If this method returns {@code false}, then at least one read operation
     * completed, successfully or otherwise. In this case, the server must not
     * close the child during a graceful shutdown, but let the exchange run to
     * its natural completion.<p>
     * 
     * The state of the child's input and output streams, and the dismissed
     * state of this reader, are irrelevant.<p>
     * 
     * The read is volatile.
     * 
     * @implNote
     * Consequently, a connection may be closed while request bytes are being
     * received. There's just no way around that (see
     * <a href="https://datatracker.ietf.org/doc/html/rfc9112#name-failures-and-timeouts">
     * RFC 9112 "9.5. Failures and Timeouts"</a>).
     */
    boolean hasNotStarted() {
        return !startedVol;
    }
    
    /**
     * Limits the number of bytes that can be read by all future iterators.<p>
     * 
     * How many bytes that may have been consumed in the past is irrelevant.<p>
     * 
     * Setting a limit will set the total number of bytes that all future
     * iterations can consume in the aggregate. If the next iterator consumes 3
     * bytes, then that will be 3 fewer bytes available for the subsequent
     * iterator.<p>
     * 
     * After the limit has been reached, {@code hasNext} will return false and
     * {@code iterator} will return an empty iterator.<p>
     * 
     * A valid value for the limit is any number from 0 (inclusive) to {@code
     * Long.MAX_VALUE} (inclusive).<p>
     * 
     * Being unlimited is the default mode of the reader, and going back to
     * unlimited requires a {@link #reset() reset}.
     * 
     * @param limit the limit
     * 
     * @return {@code this} for fluency/chaining
     * 
     * @throws IllegalStateException
     *             if this reader is dismissed
     * @throws IllegalArgumentException
     *             if {@code limit} is negative
     * @throws UnsupportedOperationException
     *             if there is already a limit set
     */
    ChannelReader limit(long limit) {
        requireNotDismissed();
        if (limit < 0) {
            throw new IllegalArgumentException("Negative limit: " + limit);
        }
        requireLimitNotSet();
        desire = limit;
        limitView();
        return this;
    }
    
    /**
     * Unset the {@link #limit(long) limit}.
     * 
     * @apiNote
     * This method was added in anticipation of a particular
     * {@code DefaultRequest.trailers()} implementation, which had to be
     * changed, because the reader was already unlimited (chunked). Today this
     * method is used only in test cases. It is not used in production nor is it
     * conceivable that it ever will.
     * 
     * @throws IllegalStateException
     *             if this reader is dismissed, or
     *             if there is no limit set, or
     *             if not empty
     */
    void reset() {
        requireNotDismissed();
        requireLimitSet();
        requireEmpty();
        desire = UNLIMITED;
        limitView();
    }
    
    /**
     * Marks this reader as not usable anymore.<p>
     * 
     * Meaning that future calls to {@code iterator} will throw an {@link
     * IllegalStateException}, and that is why this method must be called at the
     * end of the HTTP exchange.<p>
     * 
     * Assuming the reader is empty; consecutive calls to this method are NOP.
     * 
     * @throws IllegalStateException if not empty
     * 
     * @see Request
     */
    void dismiss() {
        requireEmpty();
        forceDismiss();
    }
    
    /**
     * Returns a new reader of the same channel as this one.<p>
     * 
     * An unbounded iterator can effectively read in more bytes into the buffer
     * than what a future limit allows, which would've caused the first iterator
     * in the next exchange to try and parse a message at an invalid offset.<p>
     * 
     * This method fixes the problem by handing over its buffer to the new
     * reader which will start yielding bytes right from where this reader
     * stopped. Plus, recycling the buffer reduces memory pressure (kind of
     * significantly much, this author anticipates).
     * 
     * @return a new reader of the same channel as this one
     *         (never {@code null)}
     * 
     * @see <a href="https://www.rfc-editor.org/rfc/rfc7230#section-6.3.2">
     *          RFC 7230 §6.3.2. Pipelining</a>
     * 
     * @throws IllegalStateException
     *             if this reader is not dismissed
     * @throws UnsupportedOperationException
     *             if the channel has reached end-of-stream
     */
    ChannelReader newReader() {
        requireDismissed();
        requireNotEOS();
        return new ChannelReader(src, dst, view, timeout);
    }
    
    @Override
    public long length() {
        if (view == EOS || isDismissed()) {
            return 0;
        }
        if (desire == UNLIMITED) {
            return UNLIMITED; // -1
        }
        return addExactOrCap(view.remaining(), desire());
    }
    
    @Override
    public ByteBufferIterator iterator() {
        return it;
    }
    
    private class IteratorImpl implements ByteBufferIterator
    {
        @Override
        public boolean hasNext() {
            return !isEmpty();
        }
        
        @Override
        public ByteBuffer next() throws IOException {
            requireNotDismissed();
            if (view == EOS || !hasNext()) {
                throw new NoSuchElementException();
            }
            if (view.hasRemaining()) {
                return view;
            }
            if (desire() == 0) {
                throw new NoSuchElementException();
            }
            if (view.limit() < dst.position()) {
                // Expose from buffered data
                limitView();
                assert view.hasRemaining();
                return view;
            }
            // Read from upstream
            requireVirtualThread();
            clearBuffers();
            final int r = read();
            if (r > 0) {
                limitView();
                assert view.hasRemaining();
                return view;
            } else if (r == -1) {
                return handleEOS();
            }
            throw new AssertionError(CHANNEL_BLOCKING);
        }
        
        private void clearBuffers() {
            dst.clear();
            view.clear();
        }
        
        private int read() throws IOException {
            boolean calledAbort = false;
            timeout.scheduleRead();
            try {
                return src.read(dst);
            } catch (Throwable t) {
                forceDismiss();
                assert t instanceof IOException;
                thr = (IOException) t;
                calledAbort = true;
                timeout.abort(thr);
                tryShutdownInput("Read operation failed");
                throw t;
            } finally {
                if (!started) {
                    startedVol = started = true;
                }
                if (!calledAbort) {
                    assert thr == null;
                    timeout.abort(ChannelReader.this::forceDismiss);
                }
            }
        }
        
        private ByteBuffer handleEOS() {
            tryShutdownInput("EOS");
            view = EOS;
            if (isLimitSet()) {
                forceDismiss();
                throw new EndOfStreamException();
            }
            return view;
        }
    }
    
    private void limitView() {
        if (desire == UNLIMITED) {
            // Expose as much as we have data available
            view.limit(dst.position());
        } else {
            // Well, expose as much as we can
            int ideal = addExactOrCap(view.position(), desire()),
                bound = min(ideal, dst.position());
            view.limit(bound);
            desire -= view.remaining();
        }
    }
    
    private void forceDismiss() {
        desire = DISMISSED;
    }
    
    private int desire() {
        if (desire == UNLIMITED) {
            return Integer.MAX_VALUE;
        }
        assert desire >= 0 :
            "This method must not be called if reader is dismissed.";
        return toIntOrMaxValue(desire);
    }
    
    private boolean isDismissed() {
        return desire == DISMISSED;
    }
    
    private boolean isLimitSet() {
        return desire >= 0;
    }
    
    private void requireEmpty() {
        if (!isEmpty()) {
            throw new IllegalStateException(
                "Is not empty.");
        }
    }
    
    private void requireLimitNotSet() {
        if (isLimitSet()) {
            throw new UnsupportedOperationException(
                "Limit is already set.");
        }
    }
    
    private void requireLimitSet() {
        if (!isLimitSet()) {
            throw new UnsupportedOperationException(
                "Limit has not been set.");
        }
    }
    
    private void requireDismissed() {
        if (!isDismissed()) {
            throw new IllegalStateException(
                "Reader is not dismissed.");
        }
    }
    
    private void requireNotDismissed() {
        if (isDismissed()) {
            // lol, a more user friendly message
            throw new IllegalStateException(
                "Body is already consumed or the exchange is over.");
        }
    }
    
    private void requireNotEOS() {
        if (view == EOS) {
            throw new UnsupportedOperationException(
                "Channel reached end-of-stream.");
        }
    }
    
    private static void tryShutdownInput(String why) {
        // Likely already shut down, this is more for updating our state
        var ch = channel();
        if (ch.isInputOpen()) {
            LOG.log(DEBUG, () -> why + ", shutting down input stream.");
            channel().shutdownInput();
        }
    }
}