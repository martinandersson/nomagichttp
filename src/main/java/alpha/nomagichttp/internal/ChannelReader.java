package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.EndOfStreamException;
import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.Request;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.NoSuchElementException;

import static alpha.nomagichttp.internal.Blah.CHANNEL_BLOCKING;
import static alpha.nomagichttp.internal.Blah.requireVirtualThread;
import static alpha.nomagichttp.util.Blah.addExactOrMaxValue;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.ByteBuffer.allocate;
import static java.nio.ByteBuffer.allocateDirect;

/**
 * Is a reader of bytes from a channel.<p>
 * 
 * The characteristics of this class is covered well by the JavaDoc of
 * {@link Request.Body}, who is just one of many that will over time iterate
 * bytes from this reader (i.e., the underlying child channel). Prior to the
 * request body there will be two parsers respectively for the request line and
 * headers. Following the body there may be a parser of request trailers.<p>
 * 
 * Parsers and body decoders ought to know when they have reached their
 * respective delimiter. For a body of a known length, the method
 * {@link #limit(long) limit} must be used to cap the reader, followed
 * by a call to the {@link #reset() reset} method before subscribing a parser
 * of trailers.<p>
 * 
 * The reader must be invalidated at the end of the HTTP exchange by calling the
 * method {@link #dismiss() dismiss}. This class self-dismisses on channel
 * failure and on an unexpected end-of-stream.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO:
//    1) Must ensure we have a "echo large body" test,
//       i.e. reader and writer active at the same time
//    2) Implement exception if buf is released back without new position 2x in a row
public final class ChannelReader implements ByteBufferIterable
{
    private static final System.Logger
            LOG = System.getLogger(ChannelReader.class.getPackageName());
    
    private static final int
            /** Same as BufferedOutputStream/JEP-435 */
            BUFFER_SIZE = 512,
            UNLIMITED = -1,
            DISMISSED = -2;
    
    private static final ByteBuffer EOS = allocate(0);
    
    private final ReadableByteChannel child;
    private final ByteBuffer buf;
    private       ByteBuffer view;
    private final ByteBufferIterator it;
    private long count;
    private long limit;
    
    /**
     * Constructs this object.<p>
     * 
     * Only the reader created for a channel's first HTTP exchange may be
     * created using this constructor. Successors must be derived from the
     * predecessor using {@link #newReader()}.
     * 
     * @param child channel to read from
     */
    ChannelReader(ReadableByteChannel child) {
        // The view is created with no remaining to force-read on first call to next()
        
        // The buffer is reused throughout the exchange; had we used one new
        // for each request then it would be wise to use heap buffers instead.
        // Direct "typically" has higher allocation and de-allocation cost
        // according to JDK JavaDoc.
        
        // TODO: We should profile the application at runtime. Small body sizes.
        // Maybe we should start on heap and switch to direct only when there's
        // a large body? Content type could also be an indicator (text).
        this(child, allocateDirect(BUFFER_SIZE).position(BUFFER_SIZE), null);
    }
    
    private ChannelReader(
            ReadableByteChannel child,
            ByteBuffer buf, ByteBuffer view)
    {
        this.child = child;
        this.buf   = buf;
        this.view  = view != null ? view : buf.asReadOnlyBuffer();
        this.it    = new IteratorImpl();
        this.count = 0;
        this.limit = UNLIMITED;
    }
    
    /**
     * Limits the number of bytes that can be read by all future iterators.<p>
     * 
     * How many bytes that may have been consumed in the past is irrelevant.<p>
     * 
     * Setting a limit will set the total number of bytes that all future
     * iterators can consume in the aggregate. If the next iterator consumes 3
     * bytes, then that will be 3 fewer bytes available for the subsequent
     * iterator.<p>
     * 
     * After the limit has been reached, {@code hasNext} will simply return
     * false and {@code iterator} will return an empty iterator.<p>
     * 
     * A valid value for the limit is any number from 0 (inclusive) to {@code
     * Long.MAX_VALUE} (inclusive).<p>
     * 
     * Being unlimited is the default mode of the reader, and going back to
     * unlimited requires a {@link #reset() reset}.<p>
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
        this.limit = limit;
        this.count = 0;
        return this;
    }
    
    /**
     * Unset the {@link #limit(long) limit}.<p>
     * 
     * The intended use case is to read request body trailers.
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
        limit = UNLIMITED;
    }
    
    /**
     * Marks this reader as not usable anymore.<p>
     * 
     * Future calls to {@code iterator} will throw an {@link
     * IllegalStateException}, and that is why this method must be called at the
     * end of the HTTP exchange.<p>
     * 
     * Assuming the reader is empty; consecutive calls to this method are NOP.
     * 
     * @throws IllegalStateException if not empty
     */
    void dismiss() {
        requireEmpty();
        forceDismiss();
    }
    
    private void forceDismiss() {
        limit = DISMISSED;
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
        return new ChannelReader(child, buf, view);
    }
    
    @Override
    public long length() {
        if (view == EOS || isDismissed()) {
            return 0;
        }
        if (limit == UNLIMITED) {
            return -1; // Same
        }
        return addExactOrMaxValue(view.remaining(), desireLong());
    }
    
    private long desireLong() {
        assert limit >= UNLIMITED : "Unreachable if DISMISSED";
        if (limit == UNLIMITED) {
            return Long.MAX_VALUE;
        }
        final long d = limit - count;
        assert d >= 0L : "Weird to have negative cravings";
        return d;
    }
    
    private int desireInt() {
        try {
            return Math.toIntExact(desireLong());
        } catch (ArithmeticException e) {
            return Integer.MAX_VALUE;
        }
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
            if (view.hasRemaining()) {
                return view;
            }
            if (view == EOS) {
                throw new NoSuchElementException();
            }
            final int d = desireInt();
            if (d == 0) {
                throw new NoSuchElementException();
            }
            requireVirtualThread();
            clearAndLimitBuffers(d);
            final int r = read();
            assert r != 0 : CHANNEL_BLOCKING;
            if (r == -1) {
                return handleEOS();
            }
            count = addExactOrMaxValue(count, r);
            assert view.hasRemaining();
            return view;
        }
        
        private void clearAndLimitBuffers(int desire) {
            buf.clear();
            view.clear();
            if (desire < buf.capacity()) {
                buf.limit(desire);
                view.limit(desire);
            }
        }
        
        private int read() throws IOException {
            final int v;
            try {
                v = child.read(buf);
                if (v > 0) {
                    view.limit(buf.position());
                    buf.flip();
                }
            } catch (Throwable t) {
                forceDismiss();
                shutdownInput("Read operation failed");
                throw t;
            }
            return v;
        }
        
        private ByteBuffer handleEOS() {
            shutdownInput("EOS");
            view = EOS;
            if (isLimitSet()) {
                forceDismiss();
                throw new EndOfStreamException();
            }
            return view;
        }
    }
    
    private boolean isDismissed() {
        return limit == DISMISSED;
    }
    
    private boolean isLimitSet() {
        return limit >= 0;
    }
    
    private void requireEmpty() {
        if (!isEmpty()) {
            throw new IllegalStateException(
                "Reader has contents");
        }
    }
    
    private void requireLimitNotSet() {
        if (isLimitSet()) {
            throw new UnsupportedOperationException(
                "Limit is set");
        }
    }
    
    private void requireLimitSet() {
        if (!isLimitSet()) {
            throw new UnsupportedOperationException(
                "Limit is not set");
        }
    }
    
    private void requireDismissed() {
        if (!isDismissed()) {
            throw new IllegalStateException(
                "Reader is not dismissed");
        }
    }
    
    private void requireNotDismissed() {
        if (isDismissed()) {
            // lol, a more user friendly message
            throw new IllegalStateException(
                "Body is already consumed or the exchange is over");
        }
    }
    
    private void requireNotEOS() {
        if (view == EOS) {
            throw new UnsupportedOperationException(
                "Channel reached end-of-stream");
        }
    }
    
    private static void shutdownInput(String why) {
        // Likely already shut down, this is more for updating our state
        var ch = channel();
        if (ch.isInputOpen()) {
            LOG.log(DEBUG, () -> why + ", shutting down input stream.");
            channel().shutdownInput();
        }
    }
}