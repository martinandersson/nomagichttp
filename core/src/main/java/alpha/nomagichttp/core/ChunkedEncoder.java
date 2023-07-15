package alpha.nomagichttp.core;

import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.ResourceByteBufferIterable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.NoSuchElementException;

import static java.nio.ByteBuffer.allocate;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Collections.addAll;
import static java.util.Objects.requireNonNull;

/**
 * Encodes each upstream bytebuffer into a {@code chunk} sent downstream.<p>
 * 
 * Is used by {@link ResponseProcessor} to decorate the response body if the
 * body has an unknown length and/or the response has trailers (which in
 * HTTP/1.1 requires chunked encoding; will likely not be necessary for
 * HTTP/2?).<p>
 * 
 * When the upstream turns empty, one {@code last-chunk} is sent downstream
 * before this class turns empty.<p>
 * 
 * Chunk extensions are not supported.<p>
 * 
 * The {@code trailer-part} (optional) and final {@code CRLF} is added/written
 * by {@link DefaultChannelWriter}.<p>
 * 
 * The life cycle is the same as that of {@link ChunkedDecoder}; single-use
 * only. There is one small difference; Closing {@code ChunkedDecoder} is NOP.
 * Closing {@code ChunkedEncoder} propagates to the upstream.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-4.1">RFC 7230 §4.1</a>
 */
final class ChunkedEncoder implements ResourceByteBufferIterable
{
    private static final ByteBuffer DONE = allocate(0);
    
    private final ByteBufferIterator it;
    
    ChunkedEncoder(ByteBufferIterator upstream) {
        it = new Impl(upstream);
    }
    
    @Override
    public long length() {
        return it.hasNext() ? -1 : 0;
    }
    
    @Override
    public ByteBufferIterator iterator() {
        return it;
    }
    
    private static final class Impl implements ByteBufferIterator {
        
        private ByteBufferIterator inputChunks;
        private final Deque<ByteBuffer> pipe;
        
        Impl(ByteBufferIterator upstream) {
            inputChunks = requireNonNull(upstream);
            pipe = new ArrayDeque<>();
        }
        
        @Override
        public boolean hasNext() {
            return pipe.peek() != DONE;
        }
        
        @Override
        public void close() throws IOException {
            if (inputChunks == null) {
                return;
            }
            try {
                inputChunks.close();
            } finally {
                inputChunks = null;
            }
        }
        
        @Override
        public ByteBuffer next() throws IOException {
            if (inputChunks == null) {
                // Will never happen
                throw new NoSuchElementException();
            }
            
            // Try from pipe
            var buf = take();
            if (buf != null) {
                if (buf == DONE) {
                    throw new NoSuchElementException();
                }
                return buf;
            }
            
            // Attempt encoding
            ByteBuffer chunk;
            try {
                chunk = inputChunks.next();
                if (!chunk.hasRemaining()) {
                    assert !inputChunks.hasNext();
                    throw new NoSuchElementException();
                }
            } catch (NoSuchElementException e) {
                // If upstream is done we're done
                return fill(lastChunk(), DONE).take();
            }
            
            // CRLF must follow despite size being specified, weird
            return fill(size(chunk), chunk, wrap(CRLF)).take();
        }
        
        private Impl fill(ByteBuffer one, ByteBuffer... more) {
            pipe.add(one);
            addAll(pipe, more);
            return this;
        }
        
        private ByteBuffer take() {
            var buf = pipe.poll();
            if (buf == DONE) {
                pipe.add(DONE);
                return DONE;
            }
            if (buf == null || buf.hasRemaining()) {
                // Pipe empty: nothing to take
                // Buffer with contents: take it
                return buf;
            }
            // Buf has no remaining; discard and try next
            return take();
        }
        
        // Is cached already, but this way we're guaranteed
        private static final HexFormat HF = HexFormat.of();
        private static final byte[] CRLF = {13, 10};
        
        private ByteBuffer size(ByteBuffer buf) {
            final int n = buf.remaining();
            // We could strip leading zeroes, but probably not worth the cost
            var bytes = HF.toHexDigits(n).getBytes(US_ASCII);
            return allocate(bytes.length + 2)
                    .put(bytes)
                    .put(CRLF)
                    .flip();
        }
        
        private ByteBuffer lastChunk() {
            return allocate(5)
                    // "0"
                    .put((byte) 48)
                    .put(CRLF)
                    .flip();
        }
    }
}