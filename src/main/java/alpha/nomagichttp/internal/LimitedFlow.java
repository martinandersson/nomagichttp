package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.util.concurrent.Flow;

import static java.lang.Long.MAX_VALUE;
import static java.lang.System.Logger;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

/**
 * A {@code Flow.Processor} that limits the flow of bytebuffers downstream to
 * just one at a time (maintains no buffers on its own) and completes the
 * subscription as soon as a target byte count has been read.<p>
 * 
 * @implNote
 * Limiting to just one bytebuffer at a time is not a "stop-and-wait" protocol,
 * because there is no "wait". The channel upstream do read ahead and put
 * available buffers in a queue, from which we poll. The only overhead is the
 * "communication" in the form of method calls. A negligible cost to pay for the
 * great benefit of reducing API complexity and making the life of subscribers
 * much more easy.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class LimitedFlow implements
        Flow.Processor<DefaultPooledByteBufferHolder, PooledByteBufferHolder>
{
    private static final Logger LOG = System.getLogger(LimitedFlow.class.getPackageName());
    
    private final long length;
    private Flow.Subscription upstream;
    // Is true whenever an item is in-flight; has been delivered to downstream
    // but not yet released. Volatile because T1 may do delivery and T2 may
    // release.
    private volatile boolean processing;
    // Count of bytes read by downstream. Volatile because T1 may read
    // "Subscription.request() -> tryRequest() -> desire()" and T2 may write
    // "DefaultPooledByteBufferHolder.release()". Not atomic arithmetic because
    // releasing runs exactly-once (i.e. no concurrent writes).
    private volatile long read;
    private Flow.Subscriber<? super PooledByteBufferHolder> downstream;
    private long demand;
    
    LimitedFlow(long length) {
        this.length = length;
    }
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        // TODO: LimitedFlow is exposed through the Request.Body API, we can not
        //       trust application code to behave. In order to convert
        //       "best-effort" fails into rock-solid requirements, take
        //       subscriber/subscription reference management out from
        //       AbstractUnicastPublisher to separate class shared by superclass
        //       of LimitedFlow.
        
        // Best effort only, upstream not volatile
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
                upstream.cancel();
                upstream = null;
                downstream.onComplete();
                downstream = null;
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
    
    @Override
    public void subscribe(Flow.Subscriber<? super PooledByteBufferHolder> subscriber) {
        // Best effort only, upstream not volatile
        if (upstream == null) {
            // https://github.com/reactive-streams/reactive-streams-jvm/issues/495
            throw new IllegalStateException("Nothing more to publish.");
        }
        
        (downstream = subscriber).onSubscribe(new Subscription());
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