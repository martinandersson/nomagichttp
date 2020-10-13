package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.util.Subscribers;
import alpha.nomagichttp.util.Subscriptions;

import java.util.concurrent.Flow;

import static alpha.nomagichttp.util.Subscriptions.CanOnlyBeCancelled;
import static java.lang.Long.MAX_VALUE;
import static java.lang.System.Logger;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

/**
 * A {@link  Flow.Processor} which serves a pre-defined length of bytes from an
 * upstream publisher to a downstream subscriber.<p>
 * 
 * Key characteristics have been tailored to the fact that this class serves as
 * a broker between {@link ChannelByteBufferPublisher} and {@link
 * Request.Body#subscribe(Flow.Subscriber)}:
 * <ol>
 *   <li>Throttles actual upstream demand to just one bytebuffer at a time even
 *       if the demand received from downstream is greater. A new bytebuffer
 *       will only be requested from upstream immediately after the downstream
 *       has released the previous one.</li>
 *   <li>Limits the total number of bytes pushed given a specified length and
 *       will - as soon as the length is reached; cancel upstream's subscription
 *       and complete downstream's subscriber.</li>
 *   <li>One-time use only. Re-use results in {@code IllegalStateException}.</li>
 * </ol>
 * 
 * @implNote
 * Rate-limiting to just one bytebuffer at a time is not a "stop-and-wait"
 * protocol, because there is no "wait". The channel upstream do read ahead and
 * puts available buffers in a cache/queue, from which we poll. The only
 * overhead is the "communication" in the form of method calls. A negligible
 * cost to pay for the great benefit of reducing API complexity and making the
 * life of subscribers much more easy.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class LimitedFlow implements
        Flow.Processor<DefaultPooledByteBufferHolder, PooledByteBufferHolder>
{
    private static final Logger LOG = System.getLogger(LimitedFlow.class.getPackageName());
    
    private final long length;
    private Flow.Subscription upstream;
    
    /**
     * Is true whenever an item is in-flight; has been delivered to downstream
     * but not yet released.<p>
     * 
     * When true; a request/new demand from downstream will not translate into
     * an actual request to upstream. In this case, the request will only
     * propagate after the processing completes.<p>
     * 
     * Is modified with volatile because T1 may do delivery and T2 may release.
     */
    private volatile boolean processing;
    
    /**
     * Count of bytes read by downstream.<p>
     * 
     * Is modified with volatile because T1 may read ("Subscription.request() ->
     * tryRequest() -> desire()") and T2 may write
     * ("DefaultPooledByteBufferHolder.release()").<p>
     * 
     * No need for atomic arithmetic because releasing runs exactly-once (i.e.
     * no concurrent writes).
     */
    private volatile long read;
    
    private Flow.Subscriber<? super PooledByteBufferHolder> downstream;
    private long demand;
    
    LimitedFlow(long length) {
        this.length = length;
    }
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        // Best effort only, no locks or volatiles (our code base uses a single thread)
        if (upstream != null) {
            subscription.cancel();
            // https://github.com/reactive-streams/reactive-streams-jvm/issues/495
            throw new IllegalStateException("No support for subscriber re-use.");
        }
        
        upstream = subscription;
    }
    
    @Override
    public void onNext(DefaultPooledByteBufferHolder item) {
        try {
            processing = true;
            onNext0(item);
        } catch (Throwable t) {
            processing = false;
            throw t;
        }
    }
    
    public void onNext0(DefaultPooledByteBufferHolder item) {
        final int remaining = item.get().remaining();
        if (remaining == 0) {
            LOG.log(WARNING, "Received empty bytebuffer.");
            item.release();
            return;
        }
        
        final long desire = desire();
        if (desire == 0) {
            LOG.log(DEBUG, "Received bytes although I'm done.");
            return;
        }
        
        if (desire < remaining) {
            item.limit((int) desire);
        }
        
        item.onRelease((b, readCount) -> {
            long r = read;
            read = r + readCount;
            processing = false;
            
            if (desire() == 0) {
                finish();
            } else {
                tryRequest();
            }
        });
        
        downstream.onNext(item);
    }
    
    @Override
    public void onError(Throwable t) {
        downstream.onError(t);
    }
    
    @Override
    public void onComplete() {
        downstream.onComplete();
    }
    
    // Synchronizing with finish(), because may be used by different threads
    // from untrustworthy application code.
    @Override
    public synchronized void subscribe(Flow.Subscriber<? super PooledByteBufferHolder> subscriber) {
        if (downstream != null) {
            CanOnlyBeCancelled tmp = Subscriptions.canOnlyBeCancelled();
            subscriber.onSubscribe(tmp);
            if (!tmp.isCancelled()) {
                String msg = downstream == Subscribers.noop() ?
                        "The message body bytes has already been subscribed/consumed." :
                        "Another subscription is already active.";
                subscriber.onError(new IllegalStateException(msg));
            }
        } else {
            (downstream = subscriber).onSubscribe(new Subscription());
        }
    }
    
    private long desire() {
        return length - read;
    }
    
    private void tryRequest() {
        if (!processing && demand > 0 && desire() > 0) {
            if (demand != MAX_VALUE) {
                --demand;
            }
            // This might brake §3.1 (the spec is simply put overzealous and I
            // do have my doubts about a lot of those unexplained rules)
            // https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md#3-subscription-code
            upstream.request(1);
        }
    }
    
    private synchronized void finish() {
        upstream.cancel();
        upstream = Subscriptions.noop();
        downstream.onComplete();
        downstream = Subscribers.noop();
    }
    
    private final class Subscription implements Flow.Subscription
    {
        @Override
        public void request(long n) {
            if (n <= 0) {
                throw new IllegalArgumentException("Not a positive demand: " + n);
            }
            
            demand += n;
            if (demand < 0) {
                // Overflow, cap at MAX_VALUE
                demand = MAX_VALUE;
            }
            
            tryRequest();
        }
        
        @Override
        public void cancel() {
            demand = 0;
            upstream.cancel();
        }
    }
}