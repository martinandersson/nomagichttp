package alpha.nomagichttp.core;

import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.DecoderException;
import alpha.nomagichttp.message.Request;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.NoSuchElementException;

import static alpha.nomagichttp.message.Char.toDebugString;
import static java.nio.ByteBuffer.allocate;
import static java.util.HexFormat.fromHexDigitsToLong;

/**
 * Decodes chunked-encoded bytes.<p>
 * 
 * Specifically, this class decodes any number of {@code chunk}s, followed by
 * one {@code last-chunk}.<p>
 * 
 * This class does not parse trailers, nor does it consume the final
 * {@code CRLF} terminating the {@code chunked-body}.<p>
 * 
 * Chunk extensions are discarded. As far as the author is aware, no server
 * provides support for them. Total over-engineering by the RFC lol.<p>
 * 
 * Similarly to {@link ParserOfRequestLine}'s parser, LF is the de facto line
 * terminator. A preceding CR is optional.<p>
 * 
 * LF is also the terminator when discarding chunk extensions, whose value
 * technically may be quoted and thus could technically contain CR/CRLF — which
 * would be like super weird. To safeguard against message corruption, if a
 * double-quote char is encountered whilst discarding extensions, the processor
 * blows up.<p>
 * 
 * The implementation supports single-use only. Technically, as far as this
 * class is concerned, it could have been easy to implement to start a new
 * decoding operation for each new iterator. However,
 * {@link SkeletonRequest#trailers()} is banking on a reliable implementation of
 * {@link Request.Body#isEmpty()}, and so this implementation will switch length
 * to 0 as soon as the first (and only) decoding operation is done. The
 * underlying channel reader can then be used to parse request trailers.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-4.1">RFC 7230 §4.1</a>
 */
public final class ChunkedDecoder implements ByteBufferIterable
{
    private static final int BUFFER_SIZE = 512;
    
    private final ByteBufferIterator it;
    
    ChunkedDecoder(ByteBufferIterable upstream) {
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
        private final ByteBufferIterable upstream;
        private final ByteBuffer buf;
        private final ByteBuffer view;
        private ByteBufferIterator raw;
        
        Impl(ByteBufferIterable upstream) {
            this.upstream = upstream;
            this.buf = allocate(BUFFER_SIZE).position(BUFFER_SIZE);
            this.view = buf.asReadOnlyBuffer();
            this.raw = null;
        }
        
        private static final int
                CHUNK_SIZE = 0, CHUNK_EXT = 1, CHUNK_DATA = 2, DONE = 4;
        
        private int parsing = CHUNK_SIZE;
        
        @Override
        public boolean hasNext() {
            // Either we have something already or we want to go again
            return view.hasRemaining() || parsing < DONE;
        }
        
        @Override
        public ByteBuffer next() throws IOException {
            if (raw == null) {
                raw = upstream.iterator();
            } else if (view.hasRemaining()) {
                return view;
            }
            if (parsing == DONE) {
                throw new NoSuchElementException();
            }
            buf.clear();
            view.clear();
            decode();
            view.limit(buf.position());
            assert view.hasRemaining() || parsing == DONE :
                    "Either we decoded something or we were done";
            return view;
        }
        
        private void decode() throws IOException {
            var src = getNext();
            while (parsing < DONE && src.hasRemaining() && buf.hasRemaining()) {
                final byte b = src.get();
                switch (parsing) {
                    case CHUNK_SIZE -> decodeSize(b);
                    case CHUNK_EXT  -> decodeExtensions(b);
                    case CHUNK_DATA -> decodeData(b);
                    default         -> throw new AssertionError();
                }
                // If we're not done, ask for more
                if (!src.hasRemaining() && parsing < DONE && buf.hasRemaining()) {
                    src = getNext();
                }
            }
        }
        
        private ByteBuffer getNext() throws IOException {
            var err = "Upstream is empty but decoding is not done.";
            ByteBuffer enc;
            try {
                enc = raw.next();
            } catch (NoSuchElementException e) {
                throw new DecoderException(err, e);
            }
            if (!enc.hasRemaining()) {
                throw new DecoderException(err);
            }
            return enc;
        }
        
        private static final byte CR = 13, LF = 10, SC = ';', DQ = 34;
        
        // Chunk size are encoded as hex digits
        private final CharBuffer sizeBuf = CharBuffer.allocate(16);
        // Parsed into this (bytes of current data chunk left to consume)
        private long remaining;
        
        private void decodeSize(byte b) {
            parsing = switch (b) {
                // Job done, switch to extensions
                case SC -> {
                    parseSize();
                    yield CHUNK_EXT;
                }
                // Naively ignore. E.g. chunk-size "F<CR>F" becomes "FF" lol.
                case CR -> CHUNK_SIZE;
                // Job done
                case LF -> {
                    parseSize();
                    // No remaining defines "last chunk", which has no data
                    yield hasRemaining() ? CHUNK_DATA : DONE;
                }
                // Consume and expect more
                default -> {
                    try {
                        sizeBuf.put((char) b);
                    } catch (BufferOverflowException e) {
                        throw new UnsupportedOperationException("Long overflow.", e);
                    }
                    yield CHUNK_SIZE;
                }
            };
        }
        
        private void parseSize() {
            sizeBuf.flip();
            if (sizeBuf.isEmpty()) {
                var e = new DecoderException("No chunk-size specified.");
                sizeBuf.clear();
                throw e;
            }
            long v;
            try {
                v = fromHexDigitsToLong(sizeBuf);
            } catch (NumberFormatException e) {
                throw new DecoderException(e);
            } finally {
                sizeBuf.clear();
            }
            if (v < 0) {
                throw new UnsupportedOperationException(
                        "Long overflow for hex-value \"" + sizeBuf + "\".");
            }
            remaining = v;
        }
        
        private void decodeExtensions(byte b) {
            parsing = switch (b) {
                // See JavaDoc
                case DQ ->
                    throw new UnsupportedOperationException(
                            "Quoted chunk-extension value.");
                // Job done (no remaining defines "last chunk", which has no data)
                case LF -> hasRemaining() ? CHUNK_DATA : DONE;
                // Discard
                default -> CHUNK_EXT;
            };
        }
        
        private void decodeData(byte b) {
            assert remaining >= 0;
            parsing = switch (b) {
                // May be real data (consumed), may be trailing CR (ignored)
                case CR -> {
                    if (hasRemaining()) {
                        consumeData(b);
                    }
                    yield CHUNK_DATA;
                }
                // Consume or finish chunk
                case LF -> {
                    if (hasRemaining()) {
                        consumeData(b);
                        yield CHUNK_DATA;
                    }
                    yield CHUNK_SIZE;
                }
                // Consume
                default -> {
                    if (!hasRemaining()) {
                        throw new DecoderException(
                            "Expected CR and/or LF after chunk. " +
                            "Received " + toDebugString((char) b) + ".");
                    }
                    consumeData(b);
                    // Technically we could go to CHUNK_SIZE if !hasRemaining().
                    // But the chunk is supposed to be line terminated.
                    yield CHUNK_DATA;
                }
            };
        }
        
        private boolean hasRemaining() {
            return remaining > 0;
        }
        
        private void consumeData(byte b) {
            --remaining;
            buf.put(b);
        }
    }
}