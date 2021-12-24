package alpha.nomagichttp.util;

import java.util.concurrent.Flow;

/**
 * A unicast publisher whose subscriber references are given an attachment.<p>
 * 
 * The concrete class overrides {@link #giveAttachment(Flow.Subscriber)} from
 * this class in order to produce an augmented subscriber reference with the
 * attachment, subsequently given to {@link
 * #newSubscription(SubscriberWithAttachment)}. This way, any arbitrary object
 * can be associated with the subscriber reference.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @param <T> type of published item
 * @param <A> type of attachment
 */
abstract class AugmentedAbstractUnicastPublisher<T, A>
        extends AbstractUnicastPublisher<T>
{
    protected AugmentedAbstractUnicastPublisher(boolean reusable) {
        super(reusable);
    }
    
    @Override
    public final void subscribe(Flow.Subscriber<? super T> subscriber) {
        super.subscribe(giveAttachment(subscriber));
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public SubscriberWithAttachment<T, A> get() {
        return (SubscriberWithAttachment<T, A>) super.get();
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public SubscriberWithAttachment<T, A> shutdown() {
        return (SubscriberWithAttachment<T, A>) super.shutdown();
    }
    
    protected abstract SubscriberWithAttachment<T, A>
            giveAttachment(Flow.Subscriber<? super T> subscriber);
    
    @Override
    protected final Flow.Subscription
            newSubscription(Flow.Subscriber<? super T> subscriber)
    {
        @SuppressWarnings("unchecked")
        var s = (SubscriberWithAttachment<T, A>) subscriber;
        return newSubscription(s);
    }
    
    protected abstract Flow.Subscription
            newSubscription(SubscriberWithAttachment<T, A> subscriber);
    
    static final class SubscriberWithAttachment<T, A>
            implements Flow.Subscriber<T>
    {
        private final Flow.Subscriber<? super T> d;
        private A a;
        
        SubscriberWithAttachment(Flow.Subscriber<? super T> delegate) {
            d = delegate;
        }
        
        public A attachment() {
            return a;
        }
        
        void attachment(A a) {
            this.a = a;
        }
        
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            d.onSubscribe(subscription);
        }
        
        @Override
        public void onNext(T item) {
            d.onNext(item);
        }
        
        @Override
        public void onError(Throwable throwable) {
            d.onError(throwable);
        }
        
        @Override
        public void onComplete() {
            d.onComplete();
        }
        
        @Override
        public String toString() {
            return SubscriberWithAttachment.class.getSimpleName() +
                    '{' + "d=" + d + '}';
        }
    }
}