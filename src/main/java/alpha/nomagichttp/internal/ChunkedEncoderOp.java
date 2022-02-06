package alpha.nomagichttp.internal;

import alpha.nomagichttp.util.Publishers;
import alpha.nomagichttp.util.PushPullUnicastPublisher;

import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.util.PushPullUnicastPublisher.nonReusable;
import static java.lang.System.Logger;
import static java.lang.System.Logger.Level.WARNING;
import static java.lang.System.getLogger;
import static java.nio.ByteBuffer.allocate;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

/**
 * Encodes HTTP/1.1 chunked encoding.<p>
 * 
 * Is used by {@link ResponsePipeline} if the response body has an unknown
 * length.<p>
 * 
 * Each received bytebuffer from the upstream is sent to the downstream
 * untouched, it's simply the content of what this class publishes as one
 * {@code chunk-data}. The only real work done by this class is to bloat the
 * stream with extra protocol specific bytebuffers, for example preempting each
 * chunk with a tiny {@code chunk-size} bytebuffer declaring to the end-receiver
 * the expected size of the chunk.<p>
 * 
 * There's currently no support for chunk extensions. Trailers we'll likely add
 * through Response.Builder + Response.trailers() -> Optional{@literal <}Stage
 * {@literal >}.<p>
 * 
 * This class adheres to the contract specified by {@link Publishers}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-4.1">RFC 7230 §4.1</a>
 */
class ChunkedEncoderOp implements Flow.Publisher<ByteBuffer>
{
    private static final Logger LOG = getLogger(ChunkedEncoderOp.class.getPackageName());
    
    private final Flow.Publisher<? extends ByteBuffer> up;
    
    ChunkedEncoderOp(Flow.Publisher<? extends ByteBuffer> upstream) {
        up = upstream;
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> s) {
        up.subscribe(new Encoder(s));
    }
    
    private static class Encoder implements Flow.Subscriber<ByteBuffer>
    {
        private static final ByteBuffer DONE = allocate(0);
        
        private final Flow.Subscriber<? super ByteBuffer> subscriber;
        private Flow.Subscription upstream;
        private PushPullUnicastPublisher<ByteBuffer> downstream;
        private Deque<ByteBuffer> cache;
        
        Encoder(Flow.Subscriber<? super ByteBuffer> s) {
            subscriber = requireNonNull(s);
        }
        
        @Override
        public void onSubscribe(Flow.Subscription s) {
            upstream = s;
            cache = new ConcurrentLinkedDeque<>();
            downstream = nonReusable(this::poll, cache::clear);
            downstream.subscribe(subscriber);
        }
        
        private boolean initiated;
        
        private ByteBuffer poll() {
            var b = cache.poll();
            if (b == null) {
                // TODO: When we have the Publisher builder, there should be
                // support for on-first-subscriber-request
                if (!initiated) {
                    upstream.request(2);
                    initiated = false;
                }
                return null;
            }
            if (b == DONE) {
                downstream.complete();
                cache.clear();
                return null;
            }
            return b;
        }
        
        private boolean receivedChunk = false;
        
        @Override
        public void onNext(ByteBuffer b) {
            if (!b.hasRemaining()) {
                LOG.log(WARNING, "Received empty bytebuffer.");
                return;
            }
            cache.add(size(b.remaining()));
            cache.add(b);
            // Almost a bit unbelievable to be honest, but chunk must have an
            // extra trailing CRLF despite the size already being specified lol
            cache.add(wrap(CRLF));
            downstream.announce();
            upstream.request(1);
        }
        
        @Override
        public void onError(Throwable t) {
            downstream.stop(t);
            cache.clear();
        }
        
        @Override
        public void onComplete() {
            cache.add(lastChunk());
            cache.add(DONE);
            downstream.announce();
        }
        
        // Is cached already, but this way we're guaranteed
        private static final HexFormat HF = HexFormat.of();
        private static final byte[] CRLF = {13, 10};
        
        private ByteBuffer size(int n) {
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
                    // TODO: Temporarily we end with an extra CRLF,
                    //  but trailers comes before this
                    .put(CRLF)
                    .flip();
        }
    }
}