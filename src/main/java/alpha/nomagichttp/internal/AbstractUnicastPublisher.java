package alpha.nomagichttp.internal;

import java.io.Closeable;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.lang.System.Logger.Level.*;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

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
 * Subclass is allowed to produce {@code null} items. However, it is important
 * that any thread running through the context of the publisher that notices a
 * change in a condition to the effect that a previously {@code null}-producing
 * publisher might be able to produce again, must call the {@link #announce()}
 * method which will trigger a new attempt to transfer an item to the
 * subscriber.<p>
 * 
 * The transfer logic is provided by {@link TransferOnDemand} and the same
 * guarantees and semantics {@code TransferOnDemand} specifies applies also to
 * this class which can be regarded as merely a {@code Flow.Publisher} API on
 * top of {@code TransferOnDemand}. In fact, the publisher subclass is the
 * transfer-on-demand supplier and the subscriber is the transfer-on-demand
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
 * <h2>Error Handling</h2>
 * 
 * TODO: Write something.
 * 
 * 
 * @apiNote
 * Only one subscriber at a time is allowed. The chief reason as to why this
 * decision was made is because there's simply no current need to support
 * more subscribers. Moreover, this constraint doesn't actually <i>stop</i> a
 * multi-subscriber behavior to be implemented and documented separately by a
 * "fan-out" or "pipeline" {@code Flow.Processor} subscriber. Any simplification
 * made in code is a huge win for long-term maintenance and performance.<p>
 * 
 * The only expected concrete class is {@link ChannelBytePublisher} which sits
 * on top of a byte-producing channel - which in turn, and for good reasons,
 * only support one concurrent read/write operation at a time. Allowing only one
 * subscriber brings the subscriber closer together and more aligned with the
 * channel publisher, making sure the same semantics hold true from end to
 * end.<p>
 * 
 * Having more tightly coupled semantics also makes it easier to reason about
 * the application behavior and how-to implement a "correct" subscriber. With
 * only one subscriber, it becomes quite clear that he is fully responsible for
 * processing bytes <i>in order</i> (which is probably a requirement by all
 * protocols known to man), as well as hopefully also releasing them back to a
 * potentially {@link PooledByteBufferPublisher} <i>in order</i>.<p>
 * 
 * This is especially true for bytebuffers which are not thread-safe and
 * therefore by definition not meant to be operated on at the same time by
 * different subscribers.<p>
 * 
 * "Keeping it simple" also has another advantage: pooling without reference
 * counting. Netty, for example - which essentially has "no control" over who
 * receives their buffers - use reference counting and with that comes a very
 * hard-to-understand API which is even prone to memory leaks.<p>
 * 
 * And the list goes on. For example, having the one-subscriber constraint also
 * makes it rational to implement "features" such as closing an underlying
 * channel if {@code Subscriber.onNext(T)} throws an exception. With multiple
 * subscribers, it would be a bit unfair to indiscriminately punish them all for
 * the failure of just one of them!
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
     * If {@code Subscriber.onNext} crash with an exception, then this method is
     * called (serially) with the same item.<p>
     * 
     * This gives the publisher implementation the ability to probe further and
     * possibly recycle a not fully consumed item; for future delivery to the
     * next subscriber.<p>
     * 
     * A {@link PooledByteBufferPublisher} must take note that the subscriber
     * could have <i>released</i> the bytebuffer prior to crashing; don't assume
     * the item wasn't fully consumed.<p>
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
        // TODO: clearSubscriberRef() on any error in this method
        
        // Rule 1.9: NPE the only allowed Exception to be thrown out of this method.
        requireNonNull(subscriber);
        final Flow.Subscription witnessValue;
        final RealSubscription  subscription = new RealSubscription(subscriber);
        
        if ((witnessValue = this.subscription.compareAndExchange(null, subscription)) != null) {
            Supplier<String> reason = () -> witnessValue == CLOSED ?
                        "Publisher is closed." : "Publisher has a subscriber already.";
            
            LOG.log(DEBUG, () -> "Failed to subscribe subscriber. " + reason.get());
            
            // Rule 1.9: Must call onSubscribe() before signalling error.
            // https://github.com/reactive-streams/reactive-streams-jvm/issues/487
            CanOnlyBeCancelledSubscription temp = new CanOnlyBeCancelledSubscription();
            subscriber.onSubscribe(temp);
            
            // Can only call onError() if subscription hasn't been cancelled.
            // https://github.com/reactive-streams/reactive-streams-jvm/issues/487
            if (!temp.isCancelled()) {
                // (in theory, an async cancel can happen just before we call onError() next)
                subscriber.onError(new IllegalStateException(reason.get()));
            }
        } else {
            LOG.log(DEBUG, () -> getClass().getSimpleName() + " has a new subscriber.");
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
        private final TransferOnDemand<T> mediator;
        private volatile Flow.Subscriber<? super T> subscriber;
        
        RealSubscription(Flow.Subscriber<? super T> subscriber) {
            this.mediator = new TransferOnDemand<>(AbstractUnicastPublisher.this::poll, this::push);
            this.subscriber = subscriber;
        }
        
        @Override
        public void request(long n) {
            // Rule 3.9: Value must be greater than 0, otherwise IllegalArgumentException
            try {
                mediator.increaseDemand(n);
            } catch (IllegalArgumentException e) {
                // Rule 1.6, 1.7 and 2.4: After onError, the subscription is cancelled
                mediator.finish(() ->
                         clearSubscriberRef()
                        .ifPresent(prev -> prev.onError(e)));
            }
        }
        
        @Override
        public void cancel() {
            boolean effect = mediator.finish(this::clearSubscriberRef);
            
            LOG.log(TRACE, () -> AbstractUnicastPublisher.this.getClass().getSimpleName() +
                    "'s subscriber asked to cancel the subscription. With effect: " + effect);
        }
        
        void announce() {
            mediator.tryTransfer();
        }
        
        void complete() {
            mediator.finish(() ->
                     clearSubscriberRef()
                    .ifPresent(prev -> {
                        try {
                            prev.onComplete();
                        } catch (RuntimeException e) {
                            LOG.log(WARNING, "Ignoring RuntimeException caught while calling \"Subscriber.onComplete()\".", e);
                        }
                    }));
        }
        
        private Optional<Flow.Subscriber<? super T>> clearSubscriberRef() {
            AbstractUnicastPublisher.this.subscription.set(null);
            Flow.Subscriber<? super T> prev = subscriber;
            subscriber = null;
            return ofNullable(prev);
        }
        
        private void push(T item) {
            try {
                subscriber.onNext(item);
            } catch (Throwable t) {
                cancel();
                
                try {
                    failed(item);
                } catch (Throwable t0) {
                    t0.addSuppressed(t);
                    throw t0;
                }
                
                throw t;
            }
        }
    }
}