package alpha.nomagichttp.util;

import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.nio.file.Path;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Is a thread-safe, non-blocking, and unicast publisher driven by a generator
 * function (constructor arg).<p>
 * 
 * The function is implicitly polled by the producer {@link #announce()
 * announcing} the availability of items (the push) and also polled by the
 * subscriber through the increase of his demand (the pull).<p>
 * 
 * This class is used as a simplified API for a source interested in publishing
 * items to only one active subscriber without having to manage the subscriber
 * reference or deal with concurrency issues.<p>
 * 
 * The generator function may return {@code null} which would indicate there's
 * no items available for the current subscriber at the moment (a future
 * announcement is expected).<p>
 * 
 * Due to the asynchronous nature of this class; not all items that are polled
 * out from the generator is also guaranteed to be delivered. For example, at
 * the same time a delivery just started (generator was called) a subscriber's
 * subscription may asynchronously terminate.<p>
 * 
 * If an item is polled but fails to be delivered and the item is a {@link
 * PooledByteBufferHolder}, then the item will be released (done by {@link
 * AbstractUnicastPublisher}).<p>
 * 
 * The transfer of items from the generator to the subscriber is implemented
 * using a subscriber-unique {@link SerialTransferService}. This means that for
 * a non-reusable publisher (constructor arg), the generator function will never
 * be called concurrently.<p>
 * 
 * For a reusable publisher, however, the generator function may in theory be
 * called concurrently. For example, at the same time a delivery just started,
 * the active subscriber cancel his subscription followed by a new subscriber
 * immediately polling the generator. In this particular case, the first item
 * would not be delivered because that subscriber wouldn't no longer be the
 * active subscriber.<p>
 * 
 * Subscribers of a reusable bytebuffer source (or of any other items that can
 * not be missed and must be processed orderly) should coordinate their use of
 * the source and never cancel asynchronously while there is still outstanding
 * demand. If this can not be guaranteed, the reusable publisher's generator
 * function must be thread-safe.<p>
 * 
 * An example of a reusable {@code PushPullPublisher} is the NoMagicHTTP
 * library's low-level child channel ({@code ChannelByteBufferPublisher}) which
 * publishes bytebuffers first to a request head subscriber possibly followed
 * by a request body subscriber, and so the cycle continues for as long as the
 * channel is alive. It's worth noting that the channel doesn't actually extend
 * this class, rather it is used as a final instance field to which the channel
 * delegates {@code Flow.Publisher.subscribe()} calls. Another example would be
 * {@link BetterBodyPublishers#ofFile(Path)} which use a new non-reusable {@code
 * PushPullPublisher} for each subscription of the file.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @param <T> type of item to publish
 */
public class PushPullPublisher<T> extends AugmentedAbstractUnicastPublisher<T, SerialTransferService<T>>
{
    private final Supplier<? extends T> generator;
    private final Runnable onCancel;
    
    /**
     * Constructs a {@code PushPullPublisher}.
     * 
     * @param reusable yes or no
     * @param generator item supplier
     * @throws NullPointerException if any arg is {@code null}
     */
    public PushPullPublisher(boolean reusable, Supplier<? extends T> generator) {
        super(reusable);
        this.generator = requireNonNull(generator);
        this.onCancel  = null;
    }
    
    /**
     * Constructs a non-reusable {@code PushPullPublisher}.
     * 
     * The {@code onCancel} callback executes only once and only if the
     * subscriber's cancel-signal was the signal that terminated the
     * subscription.
     * 
     * @param generator item supplier
     * @param onCancel callback
     * @throws NullPointerException if any arg is {@code null}
     */
    public PushPullPublisher(Supplier<? extends T> generator, Runnable onCancel) {
        super(false);
        this.generator = requireNonNull(generator);
        this.onCancel  = requireNonNull(onCancel);
    }
    
    /**
     * Equivalent to {@link #announce(Consumer) announce(null)}.
     */
    public void announce() {
        announce(null);
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
     * returns exceptionally, then 1) the provided {@code onError} is called,
     * 2) subscriber is signalled a {@link SubscriberFailedException}, 3) this
     * class {@link #stop() self-stop} and 4) the exception is re-thrown.<p>
     * 
     * The callback is an opportunity for the call-site to execute code before
     * the subscriber. For example, to close a read stream.<p>
     * 
     * Is NOP if no subscriber is active or an active subscriber's demand is
     * zero.
     * 
     * @param onError a chance to run error logic before the subscriber do
     *                (may be {@code null})
     */
    public void announce(Consumer<Throwable> onError) {
        ifPresent(s -> {
            try {
                s.attachment().tryTransfer();
            } catch (Throwable t) {
                if (onError != null) {
                    onError.accept(t);
                }
                errorThroughService(SubscriberFailedException.onNext(t), s);
                stop();
                throw t;
            }
        });
    }
    
    /**
     * Signal error to- and unregister the active subscriber.<p>
     * 
     * A reusable publisher may get a new subscriber even after this method
     * returns. If this is not desired, call {@link #stop(Throwable)}.<p>
     * 
     * If the receiving subscriber itself throws an exception, then the new
     * exception is logged but otherwise ignored.<p>
     * 
     * Is NOP if there is no subscriber active.
     * 
     * @param t the throwable
     * @return {@code true} only if an active subscriber will be delivered the error
     */
    public boolean error(Throwable t) {
        var s = get();
        if (s == null) {
            return false;
        }
        return errorThroughService(t, s);
    }
    
    /**
     * Signal complete to- and unregister the active subscriber.
     * 
     * A reusable publisher may get a new subscriber even after this method
     * returns. If this is not desired, call {@link #stop()}.<p>
     * 
     * Is NOP if there is no subscriber active.
     */
    public void complete() {
        ifPresent(s -> s.attachment().finish(s::onComplete));
    }
    
    /**
     * Do no longer accept new subscribers.<p>
     * 
     * An active as well as future subscribers will be signalled an {@link
     * IllegalStateException}.<p>
     * 
     * It is advisable to call {@link #stop(Throwable)} in preference over this
     * method.<p>
     * 
     * Is NOP if already stopped.
     * 
     * @return {@code true} only if an active subscriber will be delivered the error
     */
    public boolean stop() {
        return stop(new IllegalStateException());
    }
    
    /**
     * Do no longer accept new subscribers.<p>
     * 
     * An active subscriber will be signalled the given exception. Future
     * subscribers will be signalled an {@link IllegalStateException}.
     * 
     * Is NOP if already stopped.
     * 
     * @param t the throwable
     * @return {@code true} only if an active subscriber will be delivered the error
     */
    public boolean stop(Throwable t) {
        var s = shutdown();
        if (s == null) {
            return false;
        }
        return s.attachment().finish(() ->
                Subscribers.signalErrorSafe(s, t));
    }
    
    private void ifPresent(Consumer<SubscriberWithAttachment<T, SerialTransferService<T>>> action) {
        var s = get();
        if (s == null) {
            return;
        }
        action.accept(s);
    }
    
    private boolean errorThroughService(Throwable t, SubscriberWithAttachment<T, SerialTransferService<T>> s) {
        return s.attachment().finish(() -> {
            // Attempt to terminate subscription
            if (!signalError(t, s)) {
                // stale subscription, still need to communicate error to our guy
                Subscribers.signalErrorSafe(s, t);
            }
        });
    }
    
    @Override
    protected SubscriberWithAttachment<T, SerialTransferService<T>> giveAttachment(Flow.Subscriber<? super T> subscriber) {
        SubscriberWithAttachment<T, SerialTransferService<T>> s
                = new SubscriberWithAttachment<>(subscriber);
        
        s.attachment(new SerialTransferService<>(
                generator,
                item -> signalNext(item, s)));
        
        return s;
    }
    
    @Override
    protected Flow.Subscription newSubscription(SubscriberWithAttachment<T, SerialTransferService<T>> s) {
        return new DelegateToService(s);
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
            s.attachment().finish(() -> {
                if (onCancel != null) {
                    onCancel.run();
                }
            });
        }
    }
}