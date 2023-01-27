package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.BetterHeaders;
import alpha.nomagichttp.message.DecoderException;
import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BiConsumer;

import static alpha.nomagichttp.internal.ParserOf.forRequestTrailers;
import static alpha.nomagichttp.message.Char.toDebugString;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.nio.CharBuffer.allocate;
import static java.util.HexFormat.fromHexDigitsToLong;

/**
 * Decodes HTTP/1.1 chunked encoding.<p>
 * 
 * Chunk extensions are discarded. As far as I am aware, no server provides
 * support for them lol.<p>
 * 
 * Similarly to {@link ParserOfRequestLine}'s parser, LF is the de facto line
 * terminator. A preceding CR is optional.<p>
 * 
 * LF is also the terminator when discarding chunk extensions, whose value
 * technically may be quoted and thus could technically contain CR/CRLF - which
 * would be super weird. To safeguard against message corruption, if a
 * double-quote char is encountered whilst discarding extensions, the processor
 * blows up.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-4.1">RFC 7230 §4.1</a>
 */
final class ChunkedDecoderOp implements Flow.Publisher<PooledByteBufferHolder>
{
    private static final System.Logger LOG
            = System.getLogger(ChunkedDecoderOp.class.getPackageName());
    
    private static final byte CR = 13, LF = 10, SC = ';', DQ = 34;
    
    private final Flow.Publisher<? extends PooledByteBufferHolder> upstream, decoder;
    private final int maxTrailersSize;
    private final ClientChannel chApi;
    private final CompletableFuture<BetterHeaders> trailers;
    
    ChunkedDecoderOp(
            Flow.Publisher<? extends PooledByteBufferHolder> upstream,
            int maxTrailersSize, ClientChannel chApi)
    {
        this.upstream = upstream;
        this.maxTrailersSize = maxTrailersSize;
        this.chApi = chApi;
        this.trailers = new CompletableFuture<>();
        this.decoder  = new PooledByteBufferOp(upstream, new Decoder(), () -> {
            boolean parameterHasNoEffect = true;
            trailers.cancel(parameterHasNoEffect);
        });
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super PooledByteBufferHolder> s) {
        decoder.subscribe(s);
    }
    
    CompletionStage<BetterHeaders> trailers() {
        return trailers.copy();
    }
    
    private void subscribeTrailers() {
        var hs = forRequestTrailers(maxTrailersSize, chApi);
        upstream.subscribe(hs);
        hs.result().whenComplete((res, thr) -> {
            if (res != null) {
                if (!trailers.complete(res)) {
                    LOG.log(WARNING,
                        "Request-trailers finished but stage was already completed.");
                }
            } else {
                if (!trailers.completeExceptionally(thr)) {
                    LOG.log(ERROR,
                        "Request-trailers finished exceptionally but stage was already completed.", thr);
                }
            }
        });
    }
    
    private final class Decoder
            implements BiConsumer<ByteBuffer, PooledByteBufferOp.Sink>
    {
        private static final int
                CHUNK_SIZE = 0, CHUNK_EXT = 1, CHUNK_DATA = 2, DONE = 4;
        
        private int parsing = CHUNK_SIZE;
        
        @Override
        public void accept(ByteBuffer enc, PooledByteBufferOp.Sink s) {
            for (;;) {
                if (parsing == DONE) {
                    s.complete();
                    subscribeTrailers();
                    return;
                }
                final byte b;
                try {
                    b = enc.get();
                } catch (BufferUnderflowException ignore) {
                    // No remaining, await next buffer
                    return;
                }
                switch (parsing) {
                    case CHUNK_SIZE -> decodeSize(b);
                    case CHUNK_EXT  -> decodeExtensions(b);
                    case CHUNK_DATA -> decodeData(b, s);
                    default         -> throw new AssertionError();
                }
            }
        }
        
        // Chunk size are encoded as hex digits
        private final CharBuffer sizeBuf = allocate(16);
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
        
        private void decodeData(byte b, PooledByteBufferOp.Sink s) {
            assert remaining >= 0;
            parsing = switch (b) {
                // May be real data (consumed), may be trailing CR (ignored)
                case CR -> {
                    if (hasRemaining()) {
                        consumeData(b, s);
                    }
                    yield CHUNK_DATA;
                }
                // Consume or finish chunk
                case LF -> {
                    if (hasRemaining()) {
                        consumeData(b, s);
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
                    consumeData(b, s);
                    // Technically we could go to CHUNK_SIZE if !hasRemaining().
                    // But the chunk is supposed to be line terminated.
                    yield CHUNK_DATA;
                }
            };
        }
        
        private boolean hasRemaining() {
            return remaining > 0;
        }
        
        private void consumeData(byte b, PooledByteBufferOp.Sink s) {
            --remaining;
            s.accept(b);
        }
    }
}