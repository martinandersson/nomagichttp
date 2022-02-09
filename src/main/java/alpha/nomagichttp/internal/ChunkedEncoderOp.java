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
 * length and/or the response has trailers (which in HTTP/1.1 requires chunked
 * encoding; will likely not be necessary for HTTP/2).<p>
 * 
 * In the RFC, a {@code chunked-body} is composed of any number of {@code
 * chunk}s, followed by a {@code last-chunk}, followed by an optional {@code
 * trailer-part}, finally followed by {@code CRLF}. As a response body encoder,
 * this class will semantically transform each upstream bytebuffer into a {@code
 * chunk} (through preempting a size, but the bytes remain untouched) and end
 * the subscription with a {@code last-chunk}. The trailers and final {@code
 * CRLF} is written after the body and is done by {@link
 * ResponseBodySubscriber}.<p>
 * 
 * There's currently no support for chunk extensions.<p>
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
        private Deque<ByteBuffer> readable;
        
        Encoder(Flow.Subscriber<? super ByteBuffer> s) {
            subscriber = requireNonNull(s);
        }
        
        @Override
        public void onSubscribe(Flow.Subscription s) {
            upstream = s;
            readable = new ConcurrentLinkedDeque<>();
            downstream = nonReusable(this::poll, readable::clear);
            downstream.subscribe(subscriber);
        }
        
        private boolean initiated;
        
        private ByteBuffer poll() {
            var b = readable.poll();
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
                readable.clear();
                return null;
            }
            return b;
        }
        
        @Override
        public void onNext(ByteBuffer chunk) {
            if (!chunk.hasRemaining()) {
                LOG.log(WARNING, "Received empty bytebuffer.");
                return;
            }
            readable.add(size(chunk.remaining()));
            readable.add(chunk);
            // Almost a bit unbelievable to be honest, but chunk must have an
            // extra trailing CRLF despite the size already being specified lol
            readable.add(wrap(CRLF));
            downstream.announce();
            upstream.request(1);
        }
        
        @Override
        public void onError(Throwable t) {
            downstream.stop(t);
            readable.clear();
        }
        
        @Override
        public void onComplete() {
            readable.add(lastChunk());
            readable.add(DONE);
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
                    .flip();
        }
    }
}