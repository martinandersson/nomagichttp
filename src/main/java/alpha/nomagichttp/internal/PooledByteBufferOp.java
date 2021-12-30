package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.Publishers;
import alpha.nomagichttp.util.PushPullUnicastPublisher;

import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static alpha.nomagichttp.util.PushPullUnicastPublisher.nonReusable;
import static java.lang.System.Logger.Level.ERROR;
import static java.nio.ByteBuffer.allocate;
import static java.util.Objects.requireNonNull;

/**
 * A non-reusable processor of pooled byte buffers.<p>
 * 
 * A delegate function (c-tor arg) receives upstream bytebuffers, which it
 * <strong>must process synchronously</strong> into a provided byte-sink. The
 * sink implementation will put the result bytes into one or more pooled
 * bytebuffers that it sends downstream. This pattern is suitable for byte
 * encoders and decoders alike.<p>
 * 
 * An exception from the function will attempt to be sent downstream. If there
 * is no active subscriber at that time, the exception will propagate upwards
 * and be observed by whichever thread is executing the {@code onNext}
 * method.<p>
 * 
 * To honor the contract of {@link Request.Body}, only one bytebuffer at a time
 * is sent downstream.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class PooledByteBufferOp implements Flow.Publisher<PooledByteBufferHolder>
{
    interface Sink {
        /** Yield a result byte. */
        void accept(byte result);
        /** Cancel the upstream subscription and flush result bytes. */
        void complete();
    }
    
    private static final System.Logger LOG
            = System.getLogger(PooledByteBufferOp.class.getPackageName());
    
    private static final ByteBuffer NO_MORE = allocate(0);
    
    /*
     * As an inbound processor, we're consuming buffers from the
     * ChannelByteBufferPublisher, which is using a buffer size 16 * 1024. We're
     * likely inflating these bytes (e.g. decompressing), but we may also be
     * deflating (e.g. HTTP/1.1 dechunking).
     *     If inflating, we'd perhaps like to use a larger buffer, to reduce the
     * "chattiness". If deflating, we'd perhaps like to use a smaller size, to
     * minimize memory pressure. There's no saying however, that all of the
     * upstream buffer will be consumed, nor at what magnitude/level the
     * processor is inflating/deflating.
     *     As an outbound processor, we're most likely compressing. But it's
     * not expected this class is used for said purpose, since application
     * bytebuffers are not pooled, as of today there's no such API support. When
     * the server starts auto-compressing, this will change.
     *    We pick two-thirds of the channel's inbound bytebuffer size, hoping
     * it's a good trade-off.
     */
    // package-private for tests
    static final int BUF_SIZE = 10 * 1_024;
    
    private Flow.Subscription subscription;
    private final BiConsumer<ByteBuffer, Sink> logic;
    private final Deque<ByteBuffer> writable, readable;
    private final PushPullUnicastPublisher<PooledByteBufferHolder> downstream;
    
    /**
     * Initializes this object.<p>
     * 
     * This constructor subscribes to the upstream and assumes that the upstream
     * calls the {@code onSubscribe} method synchronously, as specified by
     * {@link Publishers}.
     * 
     * @param upstream bytebuffer publisher
     * @param logic actual byte processor
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws IllegalArgumentException
     *             if upstream does not signal {@code onSubscribe}
     */
    PooledByteBufferOp(
            Flow.Publisher<? extends PooledByteBufferHolder> upstream,
            BiConsumer<ByteBuffer, Sink> logic)
    {
        requireNonNull(logic);
        
        // Upstream can terminate immediately, which needs an initialized downstream
        this.readable = new ConcurrentLinkedDeque<>();
        this.downstream = nonReusable(
                this::pollReadable,
                // At this point we'll have the upstream subscription
                () -> subscription.cancel(),
                PooledByteBufferHolder::release);
        
        upstream.subscribe(new Processor());
        if (subscription == null) {
            throw new IllegalArgumentException("Received no subscription.");
        }
        
        this.logic = logic;
        this.writable = new ConcurrentLinkedDeque<>();
        writable.add(allocate(BUF_SIZE));
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super PooledByteBufferHolder> s) {
        downstream.subscribe(s);
    }
    
    // No need to be volatile,
    //     false-write before announce() - will never be missed,
    //     true-write within transfer - will always be observed.
    private boolean inflight;
    
    private final AtomicBoolean hasDemand = new AtomicBoolean();
    
    private PooledByteBufferHolder pollReadable() {
        if (inflight) {
            // Only one at a time
            return null;
        }
        // Try publish
        var buf = readable.poll();
        if (buf == null) {
            if (hasDemand.compareAndSet(false, true)) {
                subscription.request(1);
            }
            return null;
        }
        if (buf == NO_MORE) {
            downstream.complete();
            return null;
        }
        inflight = true;
        return new DefaultPooledByteBufferHolder(buf,
                readCountIgnored -> afterDownstream(buf));
    }
    
    private void afterDownstream(ByteBuffer buf) {
        inflight = false;
        releaseOrCycle(readable::addFirst, buf);
    }
    
    private boolean releaseOrCycle(Consumer<ByteBuffer> firstOrLast, ByteBuffer buf) {
        if (buf.hasRemaining()) {
            // [Re-]Release
            firstOrLast.accept(buf);
            downstream.announce();
            return true;
        } else {
            // Give to self
            buf.clear();
            writable.add(buf);
            return false;
        }
    }
    
    private final class Processor implements Flow.Subscriber<PooledByteBufferHolder> {
        private final SinkImpl sink = new SinkImpl();
        
        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
        }
        
        @Override
        public void onNext(PooledByteBufferHolder item) {
            hasDemand.set(false);
            try {
                logic.accept(item.get(), sink);
            } catch (Throwable t) {
                subscription.cancel();
                if (!downstream.stop(t)) {
                    throw t;
                }
            } finally {
                item.release();
            }
            if (!sink.flush()) {
                // May still need to request items from upstream
                // (which is only done in pollReadable!)
                downstream.announce();
            }
        }
        
        @Override
        public void onError(Throwable t) {
            if (!downstream.stop(t)) {
                LOG.log(ERROR, "No downstream received this error.", t);
            }
        }
        
        @Override
        public void onComplete() {
            readable.add(NO_MORE);
            downstream.announce();
        }
    }
    
    private final class SinkImpl implements Sink {
        private ByteBuffer buf;
        
        @Override
        public void accept(byte result) {
            if (buf == null) {
                buf = writable.poll();
                if (buf == null) {
                    buf = allocate(BUF_SIZE);
                }
            }
            assert buf.hasRemaining();
            buf.put(result);
            if (!buf.hasRemaining()) {
                flush();
            }
        }
        
        @Override
        public void complete() {
            subscription.cancel();
            flush();
            assert readable.peekLast() != NO_MORE;
            readable.add(NO_MORE);
            downstream.announce();
        }
        
        boolean flush() {
            if (buf == null) {
                return false;
            }
            var b = buf.flip();
            buf = null;
            return releaseOrCycle(readable::addLast, b);
        }
    }
}