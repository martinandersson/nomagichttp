package alpha.nomagichttp.internal;

import alpha.nomagichttp.util.AbstractUnicastPublisher;
import alpha.nomagichttp.util.SerialExecutor;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * Transforms a semi-complex web of inter-related {@code Flow.Processor}
 * components into a set of optionally overridable methods for the concrete
 * and non-reusable operator; {@code fromUpstreamNext()}, {@code
 * fromDownstreamRequest()} and so forth.<p>
 * 
 * The operator subscribes to the upstream lazily after the downstream
 * subscriber has subscribed and is installed. The concrete operator may
 * override {@link #newSubscription(Flow.Subscriber)} to be notified of the
 * downstream subscriber's arrival but must call through to super which triggers
 * the upstream subscription. The upstream can also be subscribed to eagerly by
 * calling {@link #trySubscribeToUpstream()}.<p>
 * 
 * The default behavior of each method is to pass the signal through as-is. An
 * operator should obviously override at least one of these methods in order to
 * attach its operator-specific feature. To propagate the signal forward, call
 * super, or to suppress the signal, don't.<p>
 * 
 * A few keynotes from {@code AbstractUnicastPublisher}:
 * <ul>
 *   <li>{@code fromDownstreamRequest()} is never called while the superclass is
 *       still executing {@code newSubscription()}</li>
 *   <li>The operator will only receive at most one terminating signal.</li>
 *   <li>Downstream request for demand is routed through even after subscription
 *       termination.</li>
 *   <li>If the operator signals an untrusted (i.e. non-library) upstream- or
 *       downstream asynchronously, then signals must be arranged to execute
 *       serially. Consider extending {@link Async}.</li>
 * </ul>
 * 
 * Although this class adds no logic or behavior and could be put into question
 * why it exists, the alternative would be for each operator to copy-paste a
 * bunch of repeated "infrastructure" code following the exact same pattern with
 * no other effect than to obfuscate and make it hard to follow the operator's
 * feature logic.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @param <T> type of item to publish
 * @see alpha.nomagichttp.internal
 */
abstract class AbstractOp<T> extends AbstractUnicastPublisher<T>
{
    private final Flow.Publisher<? extends T> upstream;
    private final AtomicBoolean subscribed;
    // Volatile because; set by upstream, accessed by downstream
    private volatile Flow.Subscription subscription;
    
    protected AbstractOp(Flow.Publisher<? extends T> upstream) {
        super(false);
        this.upstream = requireNonNull(upstream);
        this.subscribed = new AtomicBoolean();
    }
    
    
    protected void fromUpstreamNext(T item) {
        signalNext(item);
    }
    
    protected void fromUpstreamError(Throwable t) {
        signalError(t);
    }
    
    protected void fromUpstreamComplete() {
        signalComplete();
    }
    
    protected void fromDownstreamRequest(long n) {
        subscription.request(n);
    }
    
    protected void fromDownstreamCancel() {
        subscription.cancel();
    }
    
    @Override
    protected Flow.Subscription newSubscription(Flow.Subscriber<? super T> ignored) {
        trySubscribeToUpstream();
        return new FromDownstreamProxy();
    }
    
    protected final void trySubscribeToUpstream() {
        if (subscribed.compareAndSet(false, true)) {
            upstream.subscribe(new FromUpstreamProxy());
        }
    }
    
    private class FromUpstreamProxy implements Flow.Subscriber<T> {
        @Override public void onSubscribe(Flow.Subscription s) {
            subscription = s; }
        
        @Override public void onNext(T item) {
            fromUpstreamNext(item); }
        
        @Override public void onError(Throwable t) {
            fromUpstreamError(t); }
        
        @Override public void onComplete() {
            fromUpstreamComplete(); }
    }
    
    private class FromDownstreamProxy implements Flow.Subscription {
        @Override public void request(long n) {
            fromDownstreamRequest(n); }
        
        @Override public void cancel() {
            fromDownstreamCancel(); }
    }
    
    /**
     * Serializes all signals to upstream- and/or downstream individually.<p>
     * 
     * Only <i>fromXXX()</i> methods are serialized. Calls directly to {@code
     * AbstractUnicastPublisher} such as {@code signalNext()} are not
     * serialized.
     * 
     * @param <T> type of item
     */
    static class Async<T> extends AbstractOp<T> {
        private final SerialExecutor up, down;
        
        protected Async(Flow.Publisher<? extends T> upstream, boolean serializeUp, boolean serializeDown) {
            super(upstream);
            up   = serializeUp   ? new SerialExecutor() : null;
            down = serializeDown ? new SerialExecutor() : null;
        }
        
        protected void fromUpstreamNext(T item) {
            if (down == null) {
                super.fromUpstreamNext(item);
            } else {
                down.execute(() -> super.fromUpstreamNext(item));
            }
        }
        
        protected void fromUpstreamError(Throwable t) {
            if (down == null) {
                super.fromUpstreamError(t);
            } else {
                down.execute(() -> super.fromUpstreamError(t));
            }
        }
        
        protected void fromUpstreamComplete() {
            if (down == null) {
                super.fromUpstreamComplete();
            } else {
                down.execute(super::fromUpstreamComplete);
            }
        }
    
        protected void fromDownstreamRequest(long n) {
            if (up == null) {
                super.fromDownstreamRequest(n);
            } else {
                up.execute(() -> super.fromDownstreamRequest(n));
            }
        }
    
        protected void fromDownstreamCancel() {
            if (up == null) {
                super.fromDownstreamCancel();
            } else {
                up.execute(super::fromDownstreamCancel);
            }
        }
    }
}