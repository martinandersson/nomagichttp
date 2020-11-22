package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.ClosedPublisherException;
import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static alpha.nomagichttp.message.ClosedPublisherException.SIGNAL_FAILURE;
import static java.util.Objects.requireNonNull;

/**
 * Is a thread-safe and non-blocking publisher trait driven by a generator
 * function passed to the constructor. The function is implicitly polled by the
 * publisher subclass {@link #announce(Consumer<Throwable>) announcing} the
 * availability of items and also polled by the subscriber through the increase
 * of his demand.<p>
 * 
 * Only one subscriber at a time is allowed but many may come and go over time.
 * Rejected subscribers receive an {@code IllegalStateException}.<p>
 * 
 * The generator function may return {@code null} which would indicate there's
 * no items available for the current subscriber at the moment (a future
 * announcement is expected).<p>
 * 
 * The underlying subscriber reference is managed using an atomic reference and
 * this class guarantees that items are signalled/delivered serially to only
 * one - the active - subscriber.<p>
 * 
 * However, due to the asynchronous nature of this class; not all items that are
 * polled out from the generator is also guaranteed to be delivered. For
 * example, at the same time a delivery just started (generator was called) a
 * subscriber's subscription may asynchronously terminate (subscriber reference
 * is nullified).<p>
 * 
 * Although not very likely to happen, the generator may be called concurrently
 * and must therefore be thread-safe. For example, at the same time a delivery
 * just started, the active subscriber is asynchronously replaced from one to
 * another with the new subscriber also polling the generator. In this
 * particular case, the second item would be delivered but the first item would
 * not.<p>
 * 
 * If an item is polled but fails to be delivered and the item is a {@link
 * PooledByteBufferHolder}, then the item will be released.<p>
 * 
 * Given the above, sequential bytebuffer processors in an asynchronous
 * environment could end up processing bytes out of order and thus break message
 * integrity. Luckily for us, this isn't our environment. The library's
 * only processor ({@link RequestHeadSubscriber} is fully synchronous and
 * will possibly hand-off the channel to {@link LengthLimitedOp} which request
 * items serially one at a time.<p>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 *
 * @param <T> type of item to publish
 */
final class AnnounceToSubscriber<T>
{
    private final PollPublisher<T> impl;
    
    AnnounceToSubscriber(Supplier<? extends T> generator) {
        this.impl = new PollPublisher<>(generator);
    }
    
    /**
     * Register a subscriber.<p>
     * 
     * If a subscriber is already active, then the one specified will be
     * signalled an {@code IllegalStateException}.
     * 
     * @param subscriber to register
     * 
     * @throws NullPointerException if {@code subscriber} is {@code null}
     */
    void register(Flow.Subscriber<? super T> subscriber) {
        impl.subscribe(subscriber);
    }
    
    /**
     * Announce the presumed availability of an item from the generator
     * function.<p>
     * 
     * If a subscriber is currently active, his demand is positive and the
     * subscriber is currently not receiving any other signals, then the thread
     * calling this method will be used to execute the generator function and
     * deliver the item to the publisher.<p>
     * 
     * If this method synchronously invokes a subscriber and the subscriber
     * returns exceptionally, then 1) {@code onError} is called, 2) subscriber
     * is signalled a {@link ClosedPublisherException}, 3) this class {@link
     * #stop() self-stop} and 4) the exception is re-thrown.<p>
     * 
     * Is NOP if no subscriber is active or an active subscriber's demand is
     * zero.
     * 
     * @param onError a chance to run error logic before the subscriber do
     */
    void announce(Consumer<Throwable> onError) {
        impl.announce(onError);
    }
    
    /**
     * Signal error to- and unregister an active subscriber.<p>
     * 
     * It's possible to register a new subscriber even after this method
     * returns. If this is not desired, call {@link #stop()}.<p>
     * 
     * If the receiving subscriber itself throws an exception, then the new
     * exception is logged but otherwise ignored.<p>
     * 
     * Is NOP if there is no subscriber active.
     * 
     * @param t the throwable
     */
    void error(Throwable t) {
        impl.error(t);
    }
    
    /**
     * Do no longer accept new subscribers.<p>
     * 
     * An active subscriber will be signalled a {@link ClosedPublisherException}
     * without a message and future subscribers will be signalled an {@code
     * IllegalStateException}.<p>
     * 
     * If the active subscriber who receives a {@code ClosedPublisherException}
     * itself throws an exception, then the new exception is logged but
     * otherwise ignored.<p>
     * 
     * It is advisable to first call {@link #error(Throwable)} in order to
     * tailor the error message. Of course, a subscriber that registers
     * in-between {@code error()} and {@code stop()} would still receive the
     * message-less exception.<p>
     * 
     * Is NOP if already stopped.
     */
    void stop() {
        impl.stop();
    }
    
    private interface SubscriberWithAttachment<T, A> extends Flow.Subscriber<T> {
        A attachment();
    }
    
    private static final class PollPublisher<T> extends AugmentedAbstractUnicastPublisher<T, SerialTransferService<T>>
    {
        private final Supplier<? extends T> generator;
        
        protected PollPublisher(Supplier<? extends T> generator) {
            this.generator = requireNonNull(generator);
        }
        
        void announce(Consumer<Throwable> onError) {
            try {
                ifPresent(s -> s.attachment().tryTransfer());
            } catch (Throwable t) {
                onError.accept(t);
                // Not targeting witnessed s in particular, the reason/cause for
                // stopping stays the same even if s is replaced with someone new
                error(new ClosedPublisherException(SIGNAL_FAILURE, t));
                stop();
                throw t;
            }
        }
        
        void error(Throwable t) {
            ifPresent(s -> errorThroughService(t, s));
        }
        
        void stop() {
            var s = shutdown();
            
            if (s == null) {
                return;
            }
            
            s.attachment().finish(() ->
                    signalErrorSafe(s, new ClosedPublisherException()));
        }
        
        private void ifPresent(Consumer<SubscriberWithAttachment<T, SerialTransferService<T>>> action) {
            var s = get();
            
            if (s == null) {
                return;
            }
            
            action.accept(s);
        }
        
        @Override
        protected SubscriberWithAttachment<T, SerialTransferService<T>> giveAttachment(Flow.Subscriber<? super T> subscriber) {
            MutableSubscriberWithAttachment<T, SerialTransferService<T>> s
                    = new MutableSubscriberWithAttachment<>(subscriber);
            
            s.attachment(new SerialTransferService<>(generator, item -> {
                signalNext(item, s);
            }));
            
            return s;
        }
        
        @Override
        protected Flow.Subscription newSubscription(SubscriberWithAttachment<T, SerialTransferService<T>> s) {
            return new DelegateToService(s);
        }
        
        private void errorThroughService(Throwable t, SubscriberWithAttachment<T, SerialTransferService<T>> s) {
            s.attachment().finish(() -> {
                // Attempt to terminate subscription
                if (!signalError(t, s)) {
                    // stale subscription, still need to communicate error to our guy
                    signalErrorSafe(s, t);
                }
            });
        }
        
        private final class DelegateToService implements Flow.Subscription
        {
            private final SubscriberWithAttachment<T, SerialTransferService<T>> s;
            
            DelegateToService(SubscriberWithAttachment<T, SerialTransferService<T>> s) {
                this.s = s;
            }
            
            @Override
            public void request(long n) {
                try {
                    s.attachment().increaseDemand(n);
                } catch (IllegalArgumentException e) {
                    errorThroughService(e, s);
                }
            }
            
            @Override
            public void cancel() {
                s.attachment().finish();
            }
        }
    }
    
    private static abstract class AugmentedAbstractUnicastPublisher<T, A> extends AbstractUnicastPublisher<T>
    {
        protected AugmentedAbstractUnicastPublisher() {
            super(true);
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
        
        protected abstract SubscriberWithAttachment<T, A> giveAttachment(Flow.Subscriber<? super T> subscriber);
        
        @Override
        protected final Flow.Subscription newSubscription(Flow.Subscriber<? super T> subscriber) {
            @SuppressWarnings("unchecked")
            SubscriberWithAttachment<T, A> s = (SubscriberWithAttachment<T, A>) subscriber;
            return newSubscription(s);
        }
        
        protected abstract Flow.Subscription newSubscription(SubscriberWithAttachment<T, A> subscriber);
    }
    
    private final static class MutableSubscriberWithAttachment<T, A> implements SubscriberWithAttachment<T, A>
    {
        private final Flow.Subscriber<? super T> d;
        private A a;
    
        MutableSubscriberWithAttachment(Flow.Subscriber<? super T> delegate) {
            d = delegate;
        }
        
        @Override
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
    }
}