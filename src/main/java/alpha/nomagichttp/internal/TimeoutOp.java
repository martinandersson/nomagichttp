package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.RequestBodyTimeoutException;
import alpha.nomagichttp.message.RequestHeadTimeoutException;
import alpha.nomagichttp.message.ResponseTimeoutException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static java.lang.Long.MAX_VALUE;
import static java.util.Objects.requireNonNull;

/**
 * Timeout on item delay from upstream.<p>
 * 
 * On timeout, the upstream subscription will be cancelled and an exception
 * lazily produced by a factory will be signalled downstream (factory can also
 * be used as a "before signalling the error" callback). The timer is reset each
 * time an item is delivered from upstream.<p>
 * 
 * The two implementations {@link Flow} and {@link Pub} differ only in the
 * applied scope of the timeout - when is the timer active? {@code Flow} spans
 * from an explicit start or implicit start on first downstream increase of
 * demand, to the end of the subscription (basically always active, we expect a
 * continuous flow of items or else timeout). {@code Pub} ("publisher") is only
 * active when there is outstanding demand (i.e. focused solely on the upstream
 * publisher, downstream may take however long he wish to process the items or
 * run his own timeouts).<p>
 * 
 * The timeout signal will cancel the upstream and error out the downstream
 * <i>asynchronously</i> and so, depending on the context of the use-site -
 * whether or not the up-/downstream can handle concurrent signals - boolean
 * constructor arguments can configure the operator to serialize signals in
 * either direction.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
abstract class TimeoutOp<T> extends AbstractOp.Async<T> {
    /**
     * A timeout operator which may be manually started ahead of the first
     * downstream increase of demand and is active until the subscription
     * completes.<p>
     * 
     * Currently, used by {@link HttpExchange} to abort {@link
     * RequestHeadSubscriber} with a {@link RequestHeadTimeoutException} and
     * used by {@link DefaultRequest} to abort the body subscriber with a {@link
     * RequestBodyTimeoutException}.
     * 
     * @param <T> published item type
     */
    static final class Flow<T> extends TimeoutOp<T> {
        private final AtomicBoolean started;
        
        Flow(boolean serializeUp,
             boolean serializeDown,
             java.util.concurrent.Flow.Publisher<? extends T> upstream,
             Duration timeout,
             Supplier<? extends Throwable> exception)
        {
            super(serializeUp, serializeDown, upstream, timeout, exception);
            started = new AtomicBoolean();
        }
        
        /**
         * Start the timer.<p>
         * 
         * The start should be scheduled only after the call site has made sure
         * the timeout - which may in theory (as is the case in our tests) be
         * extremely short. Meaning, after {@link SubscriptionAsStageOp} has
         * subscribed to the timeout operator.<p>
         * 
         * Is NOP if already started.
         */
        void start() {
            tryStart();
        }
        
        @Override
        protected void fromDownstreamRequest(long n) {
            if (n > 0) {
                tryStart();
            }
            super.fromDownstreamRequest(n);
        }
        
        @Override
        protected void fromUpstreamNext(T item) {
            to.reschedule(super::timeoutAction);
            super.fromUpstreamNext(item);
        }
        
        private void tryStart() {
            if (started.compareAndSet(false, true)) {
                to.schedule(super::timeoutAction);
            }
        }
    }
    
    /**
     * A timeout operator that is only active while there is outstanding demand
     * from the downstream.<p>
     * 
     * Is foreseen to be used by {@link ResponseBodySubscriber} for producing
     * {@link ResponseTimeoutException} (downstream channel uses its own write
     * timeout).
     * 
     * @param <T> published item type
     */
    static final class Pub<T> extends TimeoutOp<T> {
        // TODO: Review "demand" atomic long usage, I think same setup is used elsewhere. DRY
        private final AtomicLong demand;
        
        Pub(boolean serializeUp,
            boolean serializeDown,
            java.util.concurrent.Flow.Publisher<? extends T> upstream,
            Duration timeout,
            Supplier<? extends Throwable> exception) {
            super(serializeUp, serializeDown, upstream, timeout, exception);
            demand = new AtomicLong(0);
        }
        
        @Override
        protected void fromUpstreamNext(T item) {
            if (demandDecrementAndGet() > 0) {
                // Still demand left, but he bought himself some more time
                to.reschedule(this::timeoutAction);
            } else {
                // No more outstanding demand, stop timer
                to.abort();
            }
            super.fromUpstreamNext(item);
        }
        
        @Override
        protected void fromDownstreamRequest(long n) {
            if (n > 0 && demandGetAndAdd(n) == 0) {
                // Changed from no demand to some demand, schedule timer
                to.schedule(this::timeoutAction);
            }
            // Let upstream error logic signal IllegalArgExc
            super.fromDownstreamRequest(n);
        }
        
        private long demandDecrementAndGet() {
            long v2 = demand.updateAndGet(v1 -> v1 == MAX_VALUE ? v1 : --v1);
            assert v2 >= 0;
            return v2;
        }
        
        private long demandGetAndAdd(long add) {
            assert add >= 0;
            if (add == 0) {
                return demand.get();
            }
            return demand.getAndUpdate(v1 ->
                    // Keep MAX_VALUE
                    v1 == MAX_VALUE ? v1 :
                            // MAX_VALUE is ceiling
                            v1 + add < 0 ? MAX_VALUE :
                                    // Safe to add
                                    v1 + add);
        }
    }
    
    protected final Timeout to;
    private final Supplier<? extends Throwable> ex;
    
    private TimeoutOp(
            boolean serializeUp,
            boolean serializeDown,
            java.util.concurrent.Flow.Publisher<? extends T> upstream,
            Duration timeout,
            Supplier<? extends Throwable> exception)
    {
        super(upstream, serializeUp, serializeDown);
        to = new Timeout(timeout);
        ex = requireNonNull(exception);
    }
    
    protected final void timeoutAction() {
        super.fromDownstreamCancel();
        Throwable err = ex.get();
        // If we fail to deliver the error, great, timeout doesn't apply without an active subscriber
        super.fromUpstreamError(err);
    }
    
    @Override
    protected final void fromUpstreamComplete() {
        to.abort();
        super.fromUpstreamComplete();
    }
    
    @Override
    protected final void fromUpstreamError(Throwable t) {
        to.abort();
        super.fromUpstreamError(t);
    }
    
    @Override
    protected final void fromDownstreamCancel() {
        to.abort();
        super.fromDownstreamCancel();
    }
}
