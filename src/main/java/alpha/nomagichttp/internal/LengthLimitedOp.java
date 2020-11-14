package alpha.nomagichttp.internal;

import java.util.concurrent.Flow;

/**
 * Restricts the total number of published bytes to a set maximum and when this
 * limit is reached, the operator will cancel the upstream and complete the
 * downstream.<p>
 * 
 * Is used by the server's request thread to make sure that the application's
 * body subscriber doesn't read beyond the message boundary.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class LengthLimitedOp extends AbstractOp<DefaultPooledByteBufferHolder>
{
    /** Configured max count of bytes published. */
    private final long max;
    
    /** Count of bytes sent downstream (may be rolled back on release if bytes remain to be read). */
    private long sent;
    
    /** Downstream's outstanding demand (transformed into "one at a time" from upstream). */
    private long demand;
    
    /** {@code true} while we're waiting on a delivery from upstream. */
    private boolean waitingOnItem;
    
    /** Collects and executes all state-modifying events serially (because coordinating all fields above was a nightmare). */
    private final SerialExecutor serially;
    
    LengthLimitedOp(long length, Flow.Publisher<DefaultPooledByteBufferHolder> upstream) {
        super(upstream);
        
        if (length < 0) {
            throw new IllegalArgumentException();
        }
        
        sent = 0;
        demand = 0;
        max = length;
        serially = new SerialExecutor();
    }
    
    @Override
    protected void fromUpstreamNext(DefaultPooledByteBufferHolder item) {
        serially.execute(() -> {
            waitingOnItem = false;
            
            if (sent == max) {
                item.release();
                return;
            }
            
            int target = prepare(item);
            sent += target;
            
            super.fromUpstreamNext(item);
        });
    }
    
    // Intercept request from downstream and add valid value to local variable
    @Override
    protected void fromDownstreamRequest(long n) {
        serially.execute(() -> {
            if (n <= 0) {
                demand = 0;
                // Chief intention here is to let upstream run his error logic
                super.fromDownstreamRequest(n);
            } else {
                demand += n;
                if (demand < 0) {
                    demand = Long.MAX_VALUE;
                }
                tryRequest();
            }
        });
    }
    
    // Overridden only to ensure serial access of subscriber
    @Override
    protected void fromUpstreamComplete() {
        serially.execute(super::fromUpstreamComplete);
    }
    
    // Overridden only to ensure serial access of subscriber
    @Override
    protected void fromUpstreamError(Throwable t) {
        serially.execute(() -> super.fromUpstreamError(t));
    }
    
    private int prepare(DefaultPooledByteBufferHolder item) {
        int desire;
        try {
            desire = Math.toIntExact(max - sent);
        } catch (ArithmeticException e) {
            desire = Integer.MAX_VALUE;
        }
        
        final int remaining = item.get().remaining();
        final int target;
        
        if (desire < remaining) {
            item.limit(desire);
            target = desire;
        } else {
            target = remaining;
        }
        assert target > 0;
        
        item.onRelease(readCount -> onRelease(readCount, target));
        return target;
    }
    
    private void onRelease(int readCount, int target) {
        serially.execute(() -> {
            final int didNotRead = target - readCount;
            if (didNotRead > 0) {
                // Rollback the count of sent bytes
                sent -= didNotRead;
            }
            
            tryRequest();
            tryFinish();
        });
    }
    
    // Possibly move 1 demand unit from local storage to upstream
    private void tryRequest() {
        if (sent < max && demand > 0 && !waitingOnItem) {
            --demand;
            waitingOnItem = true;
            super.fromDownstreamRequest(1);
        }
    }
    
    private void tryFinish() {
        if (sent == max) {
            fromDownstreamCancel();
            fromUpstreamComplete();
        }
    }
}