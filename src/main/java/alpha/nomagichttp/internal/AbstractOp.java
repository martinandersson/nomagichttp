package alpha.nomagichttp.internal;

import alpha.nomagichttp.util.AbstractUnicastPublisher;

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
 * subscriber has subscribed and been installed. The concrete operator may
 * override {@link #newSubscription(Flow.Subscriber)} to be notified of the
 * downstream subscriber arrival but must call through to super which triggers
 * the upstream subscription. The upstream can also be subscribed to eagerly by
 * calling {@code trySubscribeToUpstream()}.<p>
 * 
 * The default behavior of each method is to pass the signal through as-is. An
 * operator should obviously override at least one of these methods in order to
 * attach its operator-specific feature. To propagate the signal forward, call
 * super.<p>
 * 
 * A few key notes from {@code AbstractUnicastPublisher}: {@code
 * fromDownstreamRequest()} never while the superclass is still executing {@code
 * newSubscription()}. The operator will only receive at most one terminating
 * signal. Downstream request for demand is routed through even after
 * subscription termination.<p>
 * 
 * Although this class adds no logic or behavior and could be put into question
 * why it exists, the alternative would be for each operator to copy-paste a
 * whole bunch of repeated "infrastructure" code following the exact same
 * pattern with no other effect than to obfuscate and make it hard to follow the
 * operator's business logic.
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
}