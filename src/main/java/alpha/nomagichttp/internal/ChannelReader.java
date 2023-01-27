package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.EndOfStreamException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.Buffer;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.util.Iterator;
import java.util.NoSuchElementException;

import static alpha.nomagichttp.util.ScopedValues.clientChannel;
import static java.lang.Long.MAX_VALUE;
import static java.lang.Math.addExact;
import static java.nio.ByteBuffer.allocate;
import static java.nio.ByteBuffer.allocateDirect;
import static java.nio.charset.CodingErrorAction.REPLACE;
import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * Reads bytes from a channel.<p>
 * 
 * This class exposes an Iterable/Iterator API for simplicity but is not backed
 * by a collection. The iterator is <i>lazy</i> and will, if necessary, perform
 * channel reads into the one and only internally used direct (off-heap)
 * bytebuffer that is then returned to the consumer as a {@link Buffer
 * read-only} view.<p>
 * 
 * Therefore, <strong>never leak the buffer outside its presumed scope of
 * iteration </strong> (future mutation can impact then occurring channel
 * operations). The buffer should be either partially or fully consumed at
 * once.<p>
 * 
 * If the buffer is not fully consumed (it has bytes {@link Buffer#remaining()
 * remaining}), then the next call to {@code next} (no pun intended) will
 * shortcut the channel read operation and simply return the bytebuffer
 * immediately.<p>
 * 
 * Yielding back unconsumed bytes is by design as there will be many iterators
 * created over the lifetime of the channel reader. For example, there could be
 * one parser for the request line, followed by one parser for the request
 * headers, followed by any numbers of decoders, each delimiting a request body
 * into a stream of separate entities.<p>
 * 
 * For the yielding-back mechanism to work properly, the consumer must <strong>
 * use relative get or relative bulk get methods</strong>. Absolut methods
 * can be used to intentionally peek but not consume data.<p>
 * 
 * By the time the channel reader reaches application code, it will likely have
 * been capped to a {@link #limit(long) limit} as to prevent overconsumption
 * beyond the delimiter of a request body. Assuming the request body is not
 * followed by trailers, calls to {@code iterator} after the limit has been
 * reached will return an empty iterator.<p>
 * 
 * If a limit has not been set, the iterator will be unbounded, and it will keep
 * reading from the channel until end-of-stream, at which point {@code next}
 * returns an empty buffer. Succeeding calls to {@code hasNext} returns
 * false and succeeding calls to {@code iterator} returns an empty iterator.<p>
 * 
 * If a limit has been set and the reader reaches end-of-stream, then an {@link
 * EndOfStreamException} is thrown.<p>
 * 
 * The same underlying channel may be used for many HTTP exchanges, but the
 * channel reader instance is valid only during the execution of a single
 * exchange. A call to the {@code iterator} method after the exchange has ended
 * will throw an {@code IllegalStateException} (the reader is referred to as
 * having been "dismissed"). This is to prevent asynchronous access to a request
 * body after its enclosing exchange has ended, which had it been possible,
 * could've caused bad application code to consume bytes from a future
 * exchange.<p>
 * 
 * Both the ChannelReader and its iterator are not thread-safe. Neither must
 * ever be used concurrently. Nor must ChannelReader methods be used while an
 * iterator is active, even if by the same thread. The implementations do not
 * guard themselves against misuse, which would result in undefined application
 * behavior.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO:
//    1) Must ensure we have a "echo large body" test,
//       i.e. reader and writer active at the same time
//    2) Make into a public interface; ByteBufferReader, ByteBufferIterable?
//       Or just Request.Body extends ClientChannelReader?
//    3) Will need to figure out support for decoding/encoding; decoration, ideally
//    4) Likely public getDesire() and discard()
public final class ChannelReader implements Iterable<ByteBuffer>
{
    // Alternatively we could provide our own checked IOIterator,
    // but this would
    //     Increase number of types (always bad)
    //     Render application unable to [elegantly] do
    //         "for (buf : body)", .forEachRemaining et cetera
    //     Signal to the application that next() is a blocking call
    //     (it is, but the developer shouldn't worry about it)
    // 
    // Plus, this will provide the server with a reliable method to query about
    // the origin. We'll probably unpack before passing to the error handler.
    
    private static final int
            /** Same as BufferedOutputStream/JEP-435 */
            BUFFER_SIZE = 512,
            UNLIMITED = -1,
            DISMISSED = -2;
    
    private static final ByteBuffer EOS = allocate(0);
    
    private final ReadableByteChannel ch;
    private final ByteBuffer buf;
    private ByteBuffer view;
    private long count;
    private long limit;
    
    /**
     * Constructs a {@code ChannelReader}.<p>
     * 
     * Only the reader created for a channel's first HTTP exchange may be
     * created using this constructor. Successors must be derived from the
     * predecessor using {@link #newReader()}.
     * 
     * @param channel channel to read from
     * @throws NullPointerException if {@code channel} is {@code null}
     */
    ChannelReader(ReadableByteChannel channel) {
        // Buffer created with no remaining as to force-read on the first call to next()
        this(channel, allocateDirect(BUFFER_SIZE).position(BUFFER_SIZE), null);
    }
    
    private ChannelReader(
            ReadableByteChannel ch,
            ByteBuffer buf, ByteBuffer view)
    {
        this.ch = requireNonNull(ch);
        this.buf = buf;
        this.view = view != null ? view : buf.asReadOnlyBuffer();
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
     * @throws IllegalStateException
     *             if this reader is dismissed
     * @throws IllegalArgumentException
     *             if {@code limit} is negative
     * @throws UnsupportedOperationException
     *             if there is already a limit set
     * 
     * @see #isEmpty()
     */
    void limit(long limit) {
        requireNotDismissed();
        if (limit < 0) {
            throw new IllegalArgumentException("Negative limit: " + limit);
        }
        requireLimitNotSet();
        this.limit = limit;
        this.count = 0;
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
     * IllegalStateException}, and that is why the orchestrator must always call
     * this method at the end of the HTTP exchange.<p>
     * 
     * Assuming the reader is empty; consecutive calls to this method are NOP.
     * 
     * @throws IllegalStateException if not empty
     * 
     * @see ChannelReader
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
     * 
     * @throws UnsupportedOperationException
     *             if the channel has reached end-of-stream
     */
    ChannelReader newReader() {
        requireDismissed();
        requireNotEOS();
        return new ChannelReader(ch, buf, view);
    }
    
    /**
     * Returns true if this iterable is empty.<p>
     * 
     * This is what happens after bytes have been consumed up to a specified
     * {@link #limit(long) limit}. Otherwise, the underlying channel is never
     * considered empty.
     * 
     * @return true if this iterable is empty
     * 
     * @throws IllegalStateException
     *             if this reader is dismissed
     */
    public boolean isEmpty() {
        requireNotDismissed();
        return (!view.hasRemaining() && desireLong() == 0L) ||
                 view == EOS;
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
        if (limit != DISMISSED) {
            throw new IllegalStateException(
                "Reader is not dismissed");
        }
    }
    
    private void requireNotDismissed() {
        if (limit == DISMISSED) {
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
    
    private int desireInt() {
        try {
            return Math.toIntExact(desireLong());
        } catch (ArithmeticException e) {
            return Integer.MAX_VALUE;
        }
    }
    
    private long desireLong() {
        // Sentinel DISMISSED already checked for
        assert limit >= UNLIMITED;
        if (limit == UNLIMITED) {
            return Long.MAX_VALUE;
        }
        final long d = limit - count;
        assert d >= 0L : "Weird to have negative cravings";
        return d;
    }
    
    @Override
    public Iterator<ByteBuffer> iterator() {
        return isEmpty() ? Empty.INSTANCE : new IteratorImpl();
    }
    
    /**
     * Reads all remaining bytes.
     * 
     * @return all remaining bytes (never {@code null)}
     * 
     * @throws IllegalStateException
     *             if this reader is dismissed, or
     *             if a limit has not been set
     * 
     * @throws BufferOverflowException
     *             if the remaining bytes are more than {@code Integer.MAX_VALUE}
     */
    public ByteBuffer bytes() {
        if (isEmpty()) {
            requireLimitSet();
            return EMPTY_BYTES;
        }
        requireLimitSet();
        final long d;
        if ((d = desireLong()) > Integer.MAX_VALUE) {
            throw new BufferOverflowException();
        }
        // From direct to direct, because we don't know the final destination
        var bytes = allocateDirect((int) d);
        for (var buf : this) {
            bytes.put(buf);
        }
        return bytes.flip();
    }
    
    /**
     * Returns all remaining bytes as a char sequence.
     * 
     * @return all remaining bytes as a char sequence
     * 
     * @throws CharacterCodingException
     *             if input is malformed, or
     *             if a character is unmappable
     * 
     * @throws BufferOverflowException
     *             if the remaining bytes are more than {@code Integer.MAX_VALUE}
     *  
     * @see CharsetDecoder
     */
    public CharSequence toCharSequence() throws CharacterCodingException {
        final var raw = bytes();
        if (raw == EMPTY_BYTES) {
            return "";
        }
        
        // TODO: Use charset from request, see RequestBody.mkText
        //       And update docs, this method and toText(). Obviously.
        //       Give example of how to override. Which would be unwarranted?
        //       bytes().asCharSequence(), or StandardCharsets.XXX.decode(bytes())
        
        /*
         According to String impl
            "These SD/E objects are short-lived, the young-gen gc should be able
             to take care of them well".
         Charset.decode() says it
            "is potentially more efficient because it can cache decoders between
             successive invocations".
         hmm. Well number two is out of the question, TODAY, because with
         millions of virtual threads ThreadLocal can not be considered a cache */
        
        // TODO: Need to collect all our names in one namespace
        var smart = clientChannel().attributes().getOrCreate(
            "alpha.nomagichttp.cache-cd", () -> {
                var dumb = UTF_8.newDecoder();
                assert dumb.malformedInputAction() == REPLACE : "It wasn't dumb";
                dumb.onMalformedInput(REPORT);
                dumb.onUnmappableCharacter(REPORT);
                return dumb; });
        return smart.decode(raw);
    }
    
    /**
     * Returns all remaining bytes as a string.<p>
     * 
     * Prefer if possible, {@link #toCharSequence()} (fewer array copies).
     * 
     * @return all remaining bytes as a string
     * 
     * @throws CharacterCodingException
     *             if input is malformed, or
     *             if a character is unmappable
     * 
     * @throws BufferOverflowException
     *             if the remaining bytes are more than {@code Integer.MAX_VALUE}
     *  
     * @see CharsetDecoder
     */
    public String toText() throws CharacterCodingException {
        return toCharSequence().toString();
    }
    
    // TODO: Override toString sensibly, maybe "{type: text/plain, len: 123}"
    
    // Me not like anonymous classes and ugly symbols in the stacktrace
    private enum Empty implements Iterator<ByteBuffer> {
        INSTANCE;
        @Override public boolean hasNext() {
            return false; }
        @Override public ByteBuffer next() {
            throw new NoSuchElementException(); }
    }
    
    private static final ByteBuffer EMPTY_BYTES = allocate(0); 
    
    private class IteratorImpl implements Iterator<ByteBuffer>
    {
        @Override
        public boolean hasNext() {
            return !isEmpty();
        }
        
        @Override
        public ByteBuffer next() {
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
            if (!Thread.currentThread().isVirtual()) {
                throw new WrongThreadException("Expected virtual, is platform");
            }
            clearAndLimitBuffers(d);
            final int r = read();
            assert r != 0 : "channel is blocking";
            if (r == -1) {
                return handleEOS();
            }
            incrementCount(r);
            view.limit(buf.position());
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
        
        private int read() {
            try {
                return ch.read(buf);
            } catch (IOException e) {
                forceDismiss();
                // TODO: verify new architecture shuts down input stream
                throw new UncheckedIOException("Client operation read", e);
            } catch (Throwable t) {
                forceDismiss();
                throw t;
            }
        }
        
        private ByteBuffer handleEOS() {
            // TODO: Test if read input stream has been shutdown
            if (isLimitSet()) {
                forceDismiss();
                throw new EndOfStreamException();
            }
            return (view = EOS);
        }
        
        private void incrementCount(int v) {
            try {
                count = addExact(count, v);
            } catch (ArithmeticException e) {
                count = MAX_VALUE;
            }
        }
    }
}