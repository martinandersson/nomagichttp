package alpha.nomagichttp.internal;

import java.util.concurrent.Flow;

/**
 * Transforms a semi-complex web of inter-related {@code Flow.Processor}
 * components into a set of optionally overridable methods for the concrete
 * and non-reusable operator; {@code fromUpstreamNext()}, {@code
 * fromDownstreamRequest()} and so forth.<p>
 * 
 * The default behavior of each method is to pass the signal through as-is. An
 * operator should obviously override at least one of these methods in order to
 * attach its operator-specific feature. To propagate the signal forward, call
 * super.<p>
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
    // Volatile because; set by upstream, accessed by downstream
    private volatile Flow.Subscription upstream;
    
    protected AbstractOp(Flow.Publisher<? extends T> upstream) {
        super(false);
        upstream.subscribe(new FromUpstreamProxy());
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
        upstream.request(n);
    }
    
    protected void fromDownstreamCancel() {
        upstream.cancel();
    }
    
    
    private class FromUpstreamProxy implements Flow.Subscriber<T> {
        @Override public void onSubscribe(Flow.Subscription s) {
            upstream = s; }
        
        @Override public void onNext(T item) {
            fromUpstreamNext(item); }
        
        @Override public void onError(Throwable t) {
            fromUpstreamError(t); }
        
        @Override public void onComplete() {
            fromUpstreamComplete(); }
    }
    
    @Override
    protected final Flow.Subscription newSubscription(Flow.Subscriber<? super T> ignored) {
        return new FromDownstreamProxy();
    }
    
    private class FromDownstreamProxy implements Flow.Subscription {
        @Override public void request(long n) {
            fromDownstreamRequest(n); }
        
        @Override public void cancel() {
            fromDownstreamCancel(); }
    }
}