package alpha.nomagichttp.internal;

import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

import static java.lang.System.Logger.Level.DEBUG;

/**
 * Decorates an upstream pooled publisher with the ability to limit the number
 * of bytes sent to downstream subscriber before cancelling upstream
 * subscription and completing downstream subscriber.<p>
 * 
 * The upstream must be a {@link PooledByteBufferPublisher} and each buffer
 * published will be released immediately after delivery to the downstream
 * subscriber. This assumes the downstream subscriber processes the bytes
 * synchronously.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class LimitedBytePublisher implements Flow.Publisher<ByteBuffer>
{
    private static final System.Logger LOG = System.getLogger(LimitedBytePublisher.class.getPackageName());
    
    private final PooledByteBufferPublisher upstream;
    private final long length;
    
    /**
     * Constructs a {@code LimitedBytePublisher}.<p>
     * 
     * @param upstream  source
     * @param length    max number of bytes sent downstream
     */
    LimitedBytePublisher(PooledByteBufferPublisher upstream, long length) {
        this.upstream = upstream;
        this.length = length;
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
        upstream.subscribe(new LimitedSubscriber(subscriber));
    }
    
    private final class LimitedSubscriber implements Flow.Subscriber<ByteBuffer>
    {
        private final Flow.Subscriber<? super ByteBuffer> delegate;
        private long read;
        private Flow.Subscription subsc;
    
        LimitedSubscriber(Flow.Subscriber<? super ByteBuffer> delegate) {
            this.delegate = delegate;
            read = 0;
        }
        
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subsc = subscription;
            delegate.onSubscribe(subscription);
        }
        
        @Override
        public void onError(Throwable throwable) {
            subsc = null;
            delegate.onError(throwable);
        }
        
        @Override
        public void onComplete() {
            subsc = null;
            delegate.onComplete();
        }
        
        @Override
        public void onNext(ByteBuffer item) {
            final long desire = desire();
            
            if (desire == 0) {
                LOG.log(DEBUG, "Received bytes although I'm done.");
                return;
            }
            
            final int thenRemaining = item.remaining();
            
            if (desire < thenRemaining) {
                delegate.onNext(item.slice().limit((int) desire));
            } else {
                delegate.onNext(item);
            }
            
            read += (thenRemaining - item.remaining());
            upstream.release(item);
            
            if (desire() == 0) {
                subsc.cancel();
                subsc = null;
                delegate.onComplete();
            }
        }
        
        private long desire() {
            return length - read;
        }
    }
}