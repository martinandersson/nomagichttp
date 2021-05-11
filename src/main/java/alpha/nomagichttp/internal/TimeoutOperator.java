package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.RequestBodyTimeoutException;
import alpha.nomagichttp.message.ResponseTimeoutException;

import java.time.Duration;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static java.lang.Long.MAX_VALUE;

/**
 * Timeout on delay from upstream.<p>
 * 
 * Currently used by {@link DefaultRequest} for ending the body subscription
 * with a {@link RequestBodyTimeoutException} and closing the read stream.<p>
 * 
 * Will likely soon also be used to produce {@link
 * ResponseTimeoutException} on failure by the application's response body
 * to publish bytebuffers.<p>
 * 
 * On timeout, the upstream subscription will be cancelled and an exception
 * lazily produced by the factory will be signalled downstream (factory can also
 * be used as a "before signalling the error" callback).<p>
 * 
 * The timer is reset each time an item is delivered from upstream.<p>
 * 
 * The scope of the timer activation is either "subscription" or "publication".
 * If subscription, the timer will start as soon as {@link #start()} is called
 * and will run all the way to subscription completion. If publication, the
 * scope is reduced and the timer is active only while there is outstanding
 * demand from the downstream (in publication mode, the client code should never
 * call {@code start()}). The former suits an operator producing {@code
 * RequestBodyTimeoutException} (which applies to the entire request life
 * cycle) and the latter suits an operator producing {@code
 * ResponseTimeoutException} (which applies only to the fulfilment of batched
 * server demand from the response body publisher).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see HttpServer.Config#timeoutIdleConnection()
 */
final class TimeoutOperator<T> extends AbstractOp<T> {
    
    static <T> TimeoutOperator<T> timeoutSubscription(
            Flow.Publisher<? extends T> upstream,
            Duration timeout,
            Supplier<? extends Throwable> exc) {
        return new TimeoutOperator<>(Mode.SUB, upstream, timeout, exc);
    }
    
    static <T> TimeoutOperator<T> timeoutPublication(
            Flow.Publisher<? extends T> upstream,
            Duration timeout,
            Supplier<? extends Throwable> exc) {
        return new TimeoutOperator<>(Mode.PUB, upstream, timeout, exc);
    }
    
    private enum Mode {
        PUB, SUB;
    }
    
    private final Timeout timeout;
    private final Supplier<? extends Throwable> exc;
    private final AtomicLong demand;
    
    private TimeoutOperator(
            Mode mode,
            Flow.Publisher<? extends T> upstream,
            Duration timeout,
            Supplier<? extends Throwable> exc)
    {
        super(upstream);
        
        this.timeout  = new Timeout(timeout);
        this.exc      = exc;
        
        if (mode == Mode.SUB) {
            demand = null;
        } else {
            assert mode == Mode.PUB;
            this.demand = new AtomicLong(0);
        }
    }
    
    void start() {
        if (demand != null) {
            throw new IllegalStateException("In publication mode.");
        }
        this.timeout.schedule(this::timeoutAction);
    }
    
    @Override
    protected void fromUpstreamNext(T item) {
        timeout.abort();
        if (demand == null) {
            timeout.schedule(this::timeoutAction);
        } else if (demandDecrementAndGet() > 0) {
            timeout.schedule(this::timeoutAction);
        }
        super.fromUpstreamNext(item);
    }
    
    @Override
    protected void fromUpstreamComplete() {
        timeout.abort();
        super.fromUpstreamComplete();
    }
    
    @Override
    protected void fromUpstreamError(Throwable t) {
        timeout.abort();
        super.fromUpstreamError(t);
    }
    
    @Override
    protected void fromDownstreamRequest(long n) {
        if (n >= 0 && demand != null && demandAddAndGet(n) > 0) {
            timeout.schedule(this::timeoutAction);
        }
        // Let upstream error logic signal IllegalArgExc
        super.fromDownstreamRequest(n);
    }
    
    @Override
    protected void fromDownstreamCancel() {
        timeout.abort();
        super.fromDownstreamCancel();
    }
    
    private long demandAddAndGet(long add) {
        assert add >= 0;
        if (add == 0) {
            return demand.get();
        }
        return demand.updateAndGet(v1 ->
            // Keep MAX_VALUE
            v1 == MAX_VALUE ? v1 :
                // MAX_VALUE is ceiling
                v1 + add < 0 ? MAX_VALUE :
                    // Safe to add
                    v1 + add);
    }
    
    private long demandDecrementAndGet() {
        long v2 = demand.updateAndGet(v1 -> v1 == MAX_VALUE ? v1 : --v1);
        assert v2 >= 0;
        return v2;
    }
    
    private void timeoutAction() {
        super.fromDownstreamCancel();
        signalError(exc.get());
    }
}