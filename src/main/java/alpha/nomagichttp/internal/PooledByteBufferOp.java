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
import static java.lang.System.Logger.Level.WARNING;
import static java.nio.ByteBuffer.allocate;
import static java.util.Objects.requireNonNull;

/**
 * A non-reusable processor of pooled byte buffers.<p>
 * 
 * A delegate function (c-tor arg) - the codec - receives upstream bytebuffers,
 * which it <strong>must process synchronously</strong> into a provided
 * byte-sink. The sink implementation will put the result bytes into one or more
 * pooled bytebuffers that it sends downstream. This pattern is suitable for
 * byte encoders and decoders alike.<p>
 * 
 * It is assumed that the codec operates within a known message boundary and the
 * codec must explicitly complete the sink. If the upstream completes this
 * class' subscription normally before the codec do, an {@link AssertionError}
 * will be signalled downstream.<p> 
 * 
 * An exception from the codec will attempt to be sent downstream. If there is
 * no active subscriber at that time, the exception will propagate upwards and
 * be observed by whichever thread is executing the {@code onNext} method.<p>
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
    
    /*
     * Works like this:
     * 
     * The upstream will feed bytebuffers to the nested Processor, which in turn
     * feed the bytes to the delegate function, which in turn feed the resulting
     * bytes to the sink, which in turn will dump them in buffers it polls from
     * the <writable> queue.
     * 
     * The <writable> queue is unbounded. It'll grow however much is needed
     * until the delegate function stops yielding result bytes. Only when the
     * downstream dries up will it implicitly request only one bytebuffer from
     * the upstream. So although <writable> is unbounded, the memory held by
     * this class will effectively be bounded by the max inflated number of
     * bytes the delegate function produces from one upstream bytebuffer.
     * 
     * The sink buffer will "flush" into the <readable> queue from which the
     * downstream polls. This happens at the latest after each upstream
     * bytebuffer has finished processing, but may also happen sooner on
     * explicit complete() from the delegate function or whenever a writable
     * bytebuffer has filled up.
     * 
     * When the downstream releases a bytebuffer, it goes back into our
     * <writable> queue. And so the cycle is complete!
     */
    
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
     * minimize memory pressure. There's no saying however, that the whole
     * upstream buffer will be consumed, nor at what magnitude/level the
     * processor is inflating/deflating.
     *     As an outbound processor, we're most likely compressing. But it's
     * not expected this class is used for said purpose, since application
     * bytebuffers are not pooled, as of today there's no such API support.
     *    Today we're likely decompressing and will be doing so for some time
     * to come, so we pick two-thirds of the channel's inbound bytebuffer size,
     * hoping it's a good trade-off. In the future we'll likely provide a
     * "factor" c-tor arg which will in relation to the observed size of the
     * upstream buffer control our buffer size. E.g. "compressing with a factor
     * of 0.5" or "inflating with a factor of 2.5".
     */
    // package-private for tests
    static final int BUF_SIZE = 10 * 1_024;
    
    private Flow.Subscription subscription;
    private final SinkImpl sink;
    private final BiConsumer<ByteBuffer, Sink> codec;
    private final Runnable postmortem;
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
     * @param codec actual byte processor
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws IllegalArgumentException
     *             if upstream does not signal {@code onSubscribe}
     */
    PooledByteBufferOp(
            Flow.Publisher<? extends PooledByteBufferHolder> upstream,
            BiConsumer<ByteBuffer, Sink> codec)
    {
        this(upstream, codec, () -> {});
    }
    
    /**
     * Initializes this object.<p>
     * 
     * This constructor subscribes to the upstream and assumes that the upstream
     * calls the {@code onSubscribe} method synchronously, as specified by
     * {@link Publishers}.
     * 
     * @param upstream bytebuffer publisher
     * @param codec actual byte processor
     * @param postmortem executes on non-planned termination
     *                   (see {@link PushPullUnicastPublisher})
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws IllegalArgumentException
     *             if upstream does not signal {@code onSubscribe}
     */
    PooledByteBufferOp(
            Flow.Publisher<? extends PooledByteBufferHolder> upstream,
            BiConsumer<ByteBuffer, Sink> codec,
            Runnable postmortem)
    {
        requireNonNull(codec);
        
        // Upstream can terminate immediately, which needs an initialized downstream
        this.readable = new ConcurrentLinkedDeque<>();
        this.downstream = nonReusable(
                this::pollReadable,
                // At this point we'll have the upstream subscription
                () -> {
                    subscription.cancel();
                    postmortem.run();
                },
                PooledByteBufferHolder::release);
        
        upstream.subscribe(new Processor());
        if (subscription == null) {
            throw new IllegalArgumentException("Received no subscription.");
        }
        
        this.sink = new SinkImpl();
        this.codec = codec;
        this.postmortem = requireNonNull(postmortem);
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
            if (sink.completed()) {
                downstream.complete();
            } else {
                downstream.stop(new AssertionError(
                    "Unexpected: Channel closed gracefully before processor was done."));
                postmortem.run();
            }
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
    
    private boolean releaseOrCycle(
            Consumer<ByteBuffer> releaseFirstOrLast, ByteBuffer buf)
    {
        if (buf.hasRemaining()) {
            // [Re-]Release
            releaseFirstOrLast.accept(buf);
            downstream.announce();
            return true;
        } else {
            // Give to self
            buf.clear();
            writable.add(buf);
            return false;
        }
    }
    
    private final class Processor implements Flow.Subscriber<PooledByteBufferHolder>
    {
        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
        }
        
        @Override
        public void onNext(PooledByteBufferHolder item) {
            hasDemand.set(false);
            try {
                // Sink will release the item immediately on complete()
                // (this makes it possible for the delegate function to switch
                // an upstream subscriber on his end and have the new subscriber
                // receive remaining bytes and not "jump ahead")
                sink.processing(item);
                codec.accept(item.get(), sink);
            } catch (Throwable t) {
                subscription.cancel();
                var sent = downstream.stop(t);
                postmortem.run();
                if (!sent) {
                    throw t;
                }
            } finally {
                // release() is supposed to be NOP if released already,
                // but this way we'll save 1 volatile read in the end lol
                if (!sink.completed()) {
                    item.release();
                }
            }
            if (!sink.completed() && !sink.flush()) {
                // May need to request items from upstream
                // (which is only done in pollReadable!)
                downstream.announce();
            }
        }
        
        @Override
        public void onError(Throwable t) {
            if (!downstream.stop(t)) {
                LOG.log(WARNING, "No downstream received this error.", t);
            }
            postmortem.run();
        }
        
        @Override
        public void onComplete() {
            readable.add(NO_MORE);
            downstream.announce();
        }
    }
    
    private final class SinkImpl implements Sink {
        private ByteBuffer buf;
        private PooledByteBufferHolder item;
        private boolean completed;
        
        void processing(PooledByteBufferHolder item) {
            this.item = item;
        }
        
        boolean completed() {
            return completed;
        }
        
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
            assert !completed;
            completed = true;
            item.release();
            subscription.cancel();
            flush();
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