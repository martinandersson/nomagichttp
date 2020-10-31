package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.Subscriptions;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Is effectively a thread-safe and lock-free publisher trait configured with a
 * generator function, pulled by the subscriber through the increase in his
 * demand.<p>
 * 
 * The generator function is executed serially and may return {@code null},
 * which would indicate there's no items available for publication at the
 * moment ({@code null} is never passed forward to the subscriber). Anytime this
 * changes and the generator function may start yielding non-null items again,
 * then this must be {@link #announce() announced}.<p>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 *
 * @param <T> type of item to publish
 */
final class AnnounceToSubscriberAdapter<T>
{
    private final Impl<T> impl;
    
    protected AnnounceToSubscriberAdapter(Supplier<? extends T> generator) {
        impl = new Impl<>(generator);
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
     * returns exceptionally, then so too will this method return exceptionally
     * <i>and</i> without a future effect in regards to the life cycle of the
     * adapter which remains open.<p>
     * 
     * Is NOP if no subscriber is active or an active subscriber's demand is
     * zero.
     */
    void announce() {
        impl.announce();
    }
    
    /**
     * Signal error to- and unregister an active subscriber.<p>
     * 
     * It's possible to register a new subscriber even after this method
     * returns. If this is not wanted, call {@link #stop()}.<p>
     * 
     * If the receiving subscriber itself throws an exception, then this new
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
     * An active subscriber will be signalled a {@code RuntimeException} with
     * the message {@value AbstractUnicastPublisher2#CLOSED_MSG} and future
     * subscribers will be signalled {@code IllegalStateException}.<p>
     * 
     * If the active subscriber who receives a {@code RuntimeException} itself
     * throws an exception, then this new exception is logged but otherwise
     * ignored.<p>
     * 
     * It is advisable to first call {@link #error(Throwable)} in order to
     * tailor the error message. Of course, a subscriber that registers
     * in-between {@code error()} and {@code stop()} would still receive the
     * built-in {@code RuntimeException}.<p>
     * 
     * Is NOP if already stopped.
     */
    void stop() {
        impl.stop();
    }
    
    private static final class Impl<T> extends AbstractUnicastPublisher2<T>
    {
        private static final SerialTransferService<?> CLOSED
                = new SerialTransferService<>(() -> null, ignored -> {});
        
        @SuppressWarnings("unchecked")
        private static <T> SerialTransferService<T> T(SerialTransferService<?> sentinel) {
            return (SerialTransferService<T>) sentinel;
        }
        
        private final Supplier<? extends T> generator;
        private final AtomicReference<SerialTransferService<T>> mediator;
        
        Impl(Supplier<? extends T> generator) {
            super(true);
            this.generator = requireNonNull(generator);
            this.mediator = new AtomicReference<>();
        }
        
        void announce() {
            SerialTransferService<T> m = mediator.get();
            if (m == null || m == CLOSED) {
                return;
            }
            
            m.tryTransfer();
        }
        
        void error(Throwable t) {
            SerialTransferService<T> m = mediator.get();;
            if (m == null || m == CLOSED) {
                return;
            }
            
            m.finish(() -> signalError(t));
        }
        
        void stop() {
            final SerialTransferService<?> prev = mediator.getAndSet(T(CLOSED));
            if (prev == CLOSED) {
                return;
            }
            
            final Flow.Subscriber<? super T> sub = shutdown();
            
            if (prev != null) {
                assert sub != null :
                  "Subscriber reference was set first, then mediator and we just read them in reverse.";
                prev.finish(() -> signalErrorSafe(sub, new RuntimeException(CLOSED_MSG)));
            }
        }
        
        @Override
        protected Flow.Subscription newSubscription(Flow.Subscriber<? super T> sub) {
            SerialTransferService<T> m = mediator.getAndUpdate(v -> v == CLOSED ? T(CLOSED) :
                    new SerialTransferService<>(generator, this::signalNext));
            
            if (m == CLOSED) {
                signalErrorSafe(sub, new RuntimeException(CLOSED_MSG));
                return Subscriptions.noop();
            }
            
            return new DelegateToService(m);
        }
        
        private final class DelegateToService implements Flow.Subscription
        {
            private final SerialTransferService<?> service;
            
            DelegateToService(SerialTransferService<?> service) {
                this.service = service;
            }
            
            @Override
            public void request(long n) {
                try {
                    service.increaseDemand(n);
                } catch (IllegalArgumentException e) {
                    service.finish(() -> signalError(e));
                }
            }
            
            @Override
            public void cancel() {
                service.finish();
            }
        }
    }
}