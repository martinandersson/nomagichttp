package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.DecoderException;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.NoSuchElementException;

import static alpha.nomagichttp.message.Char.toDebugString;
import static java.nio.ByteBuffer.allocate;
import static java.util.HexFormat.fromHexDigitsToLong;

/**
 * Decodes HTTP/1.1 chunked encoding.<p>
 * 
 * Chunk extensions are discarded. As far as the author is aware, no server
 * provides support for them. Total over-engineering by the RFC lol<p>
 * 
 * Similarly to {@link ParserOfRequestLine}'s parser, LF is the de facto line
 * terminator. A preceding CR is optional.<p>
 * 
 * LF is also the terminator when discarding chunk extensions, whose value
 * technically may be quoted and thus could technically contain CR/CRLF — which
 * would be like super weird. To safeguard against message corruption, if a
 * double-quote char is encountered whilst discarding extensions, the processor
 * blows up.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-4.1">RFC 7230 §4.1</a>
 */
public final class ChunkedDecoder implements ByteBufferIterable
{
    private static final int BUFFER_SIZE = 512;
    
    /*
     * Technically, as far as this class is concerned, we could've always
     * returned length -1 and a new iterator for each client.
     *    However, by caching the iterator, we are now able to switch length to
     * 0 after the last chunk. This may or may not be a benefit to the
     * application code, but it does matter to DefaultRequest.trailers() which
     * is banking on a reliable implementation of Request.Body.isEmpty().
     */
    
    private final ByteBufferIterable upstream;
    private ByteBufferIterator it;
    
    ChunkedDecoder(ByteBufferIterable upstream) {
        this.upstream = upstream;
    }
    
    @Override
    public long length() {
        // Any content we're aware of, length is unknown
        if (it != null && it.hasNext()) {
            return -1;
        }
        // We know we're empty only if upstream has no more
        return upstream.length() == 0 ? 0 : -1;
    }
    
    @Override
    public ByteBufferIterator iterator() {
        // While we have remaining in the view, keep returning it
        if (it != null && it.hasNext()) {
            return it;
        }
        // Otherwise we attempt a new subscription
        return (it = new Impl());
    }
    
    private final class Impl implements ByteBufferIterator {
        
        private final ByteBuffer buf = allocate(BUFFER_SIZE).position(BUFFER_SIZE);
        private final ByteBuffer view = buf.asReadOnlyBuffer();
        private ByteBufferIterator raw;
        
        private static final int
                CHUNK_SIZE = 0, CHUNK_EXT = 1, CHUNK_DATA = 2, DONE = 4;
        
        private int parsing = CHUNK_SIZE;
        
        @Override
        public boolean hasNext() {
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
            return view;
        }
        
        private void decode() throws IOException {
            var enc = getNext();
            while (enc.hasRemaining() && buf.hasRemaining()) {
                final byte b = enc.get();
                switch (parsing) {
                    case CHUNK_SIZE -> decodeSize(b);
                    case CHUNK_EXT  -> decodeExtensions(b);
                    case CHUNK_DATA -> decodeData(b);
                    default         -> throw new AssertionError();
                }
                // If we're not done, ask for more
                if (parsing < DONE && buf.hasRemaining() && !enc.hasRemaining()) {
                    enc = getNext();
                }
            }
        }
        
        private ByteBuffer getNext() throws IOException {
            var enc = raw.next();
            if (!enc.hasRemaining()) {
                // TODO: CodecException
                throw new RuntimeException(
                        "Channel presumably reached end-of-stream but decoding is not done");
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