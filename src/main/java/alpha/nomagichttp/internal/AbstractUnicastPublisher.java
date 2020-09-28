package alpha.nomagichttp.internal;

import java.io.Closeable;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.TRACE;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Objects.requireNonNull;

/**
 * Provides all the subscriber and subscription plumbing for the concrete
 * {@link Flow.Publisher} implementation, facilitated through the subclass
 * explicitly <i>{@link #announce() announcing}</i> the availability of an item
 * and the subscriber implicitly <i>{@link #poll() polling}</i> the subclass by
 * increasing his demand.<p>
 * 
 * This class allows only one subscriber at any given time. An attempt to
 * subscribe while a subscription is already active will result in an
 * {@code IllegalStateException} being signalled to the rejected subscriber.<p>
 * 
 * Subclass is allowed to produce {@code null} items which in turn will not be
 * delivered to the subscriber. However, it is important that any thread running
 * through the context of the publisher that notices a change in a condition to
 * the effect that a previously {@code null}-producing publisher might be able
 * to produce again, must call the {@link #announce()} method which will trigger
 * a new attempt to transfer an item to the subscriber.<p>
 * 
 * The transfer logic is implemented by {@link SerialTransferService} and the
 * same guarantees and semantics {@code SerialTransferService} specifies applies
 * also to this class which can be regarded as merely a {@code Flow.Publisher}
 * API on top of {@code SerialTransferService}. In fact, the publisher subclass
 * is the transfer-service supplier and the subscriber is the transfer-service
 * consumer.<p>
 * 
 * This class implements {@link Closeable} and the default close-behavior is
 * to 1) complete an active subscriber and 2) reject future subscribers.
 * Subclasses who overrides {@code close()} <strong>must</strong> call through
 * to super.<p>
 * 
 * 
 * <h2>Thread Safety</h2>
 * 
 * The {@code announce} method and the {@code close} method implementation
 * provided by this class, is thread-safe. Overrides of the methods {@code poll}
 * and {@link #failed(Object) failed} doesn't need to be thread-safe since they
 * will be called serially within the scope of an attempt to transfer.<p>
 * 
 * 
 * @apiNote
 * Only one subscriber at a time is allowed. This decision was made in order to
 * better suite publishers of bytebuffers; which are not thread-safe and carries
 * bytes that most likely should be processed sequentially. This constraint does
 * not <i>stop</i> a multi-subscriber behavior to be implemented and documented
 * separately by a "fan-out" or "pipeline" {@code Flow.Processor} subscriber.
 * 
 * @param <T> type of item to publish
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Test this guy against the TCK
// https://github.com/reactive-streams/reactive-streams-jvm/tree/master/tck
abstract class AbstractUnicastPublisher<T> implements Flow.Publisher<T>, Closeable
{
    // Whenever a "rule" is referred to in source-code comments inside this file,
    // the rule should be found here (if not, this implementation is outdated):
    // https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md
    
    private static final System.Logger LOG = System.getLogger(AbstractUnicastPublisher.class.getPackageName());
    private static final Flow.Subscription CLOSED = new NoOperationSubscription();
    
    private final AtomicReference<Flow.Subscription> subscription;
    
    AbstractUnicastPublisher() {
        this.subscription = new AtomicReference<>();
    }
    
    /**
     * Concrete class announces that he might now be able to publish an item.
     * 
     * @see AbstractUnicastPublisher
     */
    protected final void announce() {
        Flow.Subscription sub = subscription.get();
        
        if (sub instanceof AbstractUnicastPublisher.RealSubscription) {
            @SuppressWarnings("unchecked")
            RealSubscription typed = (RealSubscription) sub;
            typed.announce();
        }
    }
    
    /**
     * Subscriber poll the publisher as long as there is demand for more
     * items.<p>
     * 
     * The returned item may be {@code null} if publisher is unable to produce
     * at the moment.
     * 
     * This method is called by this class only and should not be called
     * anywhere else.
     * 
     * @return the item to publish
     */
    protected abstract T poll();
    
    /**
     * If {@code Subscriber.onNext} returns exceptionally, then this method is
     * called (serially) with the same item.<p>
     * 
     * This gives the publisher implementation the ability to probe further and
     * possibly recycle a not fully consumed item; for future delivery to the
     * next subscriber.<p>
     * 
     * Overriding this method won't stop the exception from propagating to the
     * active thread performing the subscriber delivery.<p>
     * 
     * This method is <i>not</i> called for in-flight items when the publisher
     * closes/completes or the subscription cancels. Reason being that the
     * delivery of an item can not be aborted.<p>
     * 
     * This method is called by this class only and should not be called
     * anywhere else.
     * 
     * @param item that was delivered to a crashing {@code Subscriber.onNext}
     */
    protected void failed(T item) {
        // Overridden if implementation cares about this
    }
    
    @Override
    public void close() {
        Flow.Subscription sub = subscription.getAndSet(CLOSED);
        
        if (sub instanceof AbstractUnicastPublisher.RealSubscription) {
            @SuppressWarnings("unchecked")
            RealSubscription typed = (RealSubscription) sub;
            typed.complete();
        }
    }
    
    @Override
    public final void subscribe(Flow.Subscriber<? super T> subscriber) {
        try {
            subscribe0(subscriber);
        } catch (Throwable t) {
            subscription.set(null);
        }
    }
    
    public final void subscribe0(Flow.Subscriber<? super T> subscriber) {
        // Rule 1.9: NPE the only allowed Exception to be thrown out of this method.
        requireNonNull(subscriber);
        final Flow.Subscription witnessValue;
        final RealSubscription  subscription = new RealSubscription(subscriber);
        
        if ((witnessValue = this.subscription.compareAndExchange(null, subscription)) != null) {
            Supplier<String> reason = () -> witnessValue == CLOSED ?
                        "Publisher is closed." : "Publisher has a subscriber already.";
            
            LOG.log(DEBUG, () -> "Failed to subscribe " + subscriber + ". " + reason.get());
            
            // Rule 1.9: Must call onSubscribe() before signalling error.
            // https://github.com/reactive-streams/reactive-streams-jvm/issues/487
            CanOnlyBeCancelledSubscription temp = new CanOnlyBeCancelledSubscription();
            subscriber.onSubscribe(temp);
            
            // Can only call onError() if subscription hasn't been cancelled.
            // https://github.com/reactive-streams/reactive-streams-jvm/issues/487
            if (!temp.isCancelled()) {
                // This is a tremendously lame way of throwing this type of exception.
                // TODO: Break away from the spec; don't call onSubscribe() for rejected
                //       subscribers and throw IllegalStateEx and IllegalArgumentEx
                //       (Subscription.request) immediately. This must also be documented
                //       in javadoc of Request.
                // https://github.com/reactive-streams/reactive-streams-jvm/issues/495
                subscriber.onError(new IllegalStateException(reason.get()));
            }
        } else {
            LOG.log(DEBUG, () -> this + " has a new subscriber: " + subscriber);
            subscriber.onSubscribe(subscription);
        }
    }
    
    private static final class NoOperationSubscription implements Flow.Subscription
    {
        @Override
        public void request(long n) {
            // Empty
        }
        
        @Override
        public void cancel() {
            // Empty
        }
    }
    
    private static final class CanOnlyBeCancelledSubscription implements Flow.Subscription
    {
        private volatile boolean cancelled;
        
        @Override
        public void request(long n) {
            // Empty
        }
        
        @Override
        public void cancel() {
            cancelled = true;
        }
        
        boolean isCancelled() {
            return cancelled;
        }
    }
    
    private final class RealSubscription implements Flow.Subscription
    {
        private final SerialTransferService<T> mediator;
        private volatile Flow.Subscriber<? super T> subscriber;
        
        RealSubscription(Flow.Subscriber<? super T> subscriber) {
            this.mediator = new SerialTransferService<>(AbstractUnicastPublisher.this::poll, this::push);
            this.subscriber = subscriber;
        }
        
        @Override
        public void request(long n) {
            // Rule 3.9: Value must be greater than 0, otherwise IllegalArgumentException
            try {
                mediator.increaseDemand(n);
            } catch (IllegalArgumentException e) {
                // Rule 1.6, 1.7 and 2.4: After onError, the subscription is cancelled
                if (mediator.finish()) {
                    getAndClearSubscriberRef().onError(e);
                }
            }
        }
        
        @Override
        public void cancel() {
            boolean success = mediator.finish();
            if (success) {
                getAndClearSubscriberRef();
            }
            LOG.log(TRACE, () -> AbstractUnicastPublisher.this +
                    "'s subscriber asked to cancel the subscription. With effect: " + success);
        }
        
        void announce() {
            mediator.tryTransfer();
        }
        
        void complete() {
            if (mediator.finish()) {
                try {
                    getAndClearSubscriberRef().onComplete();
                } catch (RuntimeException e) {
                    LOG.log(WARNING, "Ignoring RuntimeException caught while calling \"Subscriber.onComplete()\".", e);
                }
            }
        }
        
        private Flow.Subscriber<? super T> getAndClearSubscriberRef() {
            subscription.set(null);
            try {
                return subscriber;
            } finally {
                subscriber = null;
            }
        }
        
        private void push(T item) {
            try {
                subscriber.onNext(item);
            } catch (Throwable t) {
                cancel();
                
                try {
                    failed(item);
                } catch (Throwable next) {
                    t.addSuppressed(next);
                }
                
                throw t;
            }
        }
    }
}