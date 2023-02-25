package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.ByteBufferIterator.Empty;
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

final class ChunkedEncoder implements ResourceByteBufferIterable
{
    private static final ByteBuffer DONE = allocate(0);
    
    private final ByteBufferIterator it;
    
    ChunkedEncoder(ByteBufferIterator upstream) {
        it = new Impl(upstream);
    }
    
    @Override
    public long length() {
        return it == null ? 0 : -1;
    }
    
    @Override
    public ByteBufferIterator iterator() {
        return it == null ? Empty.INSTANCE : it;
    }
    
    private final class Impl implements ByteBufferIterator {
        
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