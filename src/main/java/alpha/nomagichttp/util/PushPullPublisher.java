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
 * This class honors the contract specified in {@link Publishers}.<p>
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
 * no items available for the current subscriber at that moment in time (a
 * future announcement is expected).<p>
 * 
 * Not all items that are polled out from the generator are also guaranteed to
 * be successfully delivered. Quite obviously, the subscriber's {@code onNext()}
 * method may return exceptionally. It could also be the case, that at the same
 * time a delivery just started (generator was called) a subscriber's
 * subscription asynchronously terminates. Each constructor in this class
 * accepts a "non-successful delivery" callback which will be called if the item
 * was not successfully delivered. May be used, for example to release a {@link
 * PooledByteBufferHolder}.<p>
 * 
 * If a subscriber's {@code onNext} method returns exceptionally, this class
 * will {@link #shutdown()}.<p>
 * 
 * For a non-reusable publisher, the generator function will never be called
 * concurrently (by this class, superclass, or subscriber.<p>
 * 
 * For a reusable publisher, however, the generator function may in theory be
 * called concurrently. For example, at the same time a delivery just started,
 * the active subscriber cancels his subscription followed by a new subscriber
 * immediately polling the generator. In this particular case, the first item
 * would not be delivered because the intended subscriber is no longer the
 * active subscriber (each subscription is a unique relationship maintained by
 * {@link SerialTransferService}).<p>
 * 
 * An example of a reusable {@code PushPullPublisher} is the HTTP server's
 * low-level child channel ({@code ChannelByteBufferPublisher}) which publishes
 * bytebuffers first to a request head subscriber possibly followed by a request
 * body subscriber. It's worth noting that the channel doesn't actually
 * extend this class, rather it is used as a final instance field to which the
 * channel delegates {@code Flow.Publisher.subscribe()} calls.<p>
 * 
 * An example of a non-reusable {@code PushPullPublisher} would be {@link
 * BetterBodyPublishers#ofFile(Path)} which internally use a new delegate for
 * each subscription of the file. So technically, the publisher itself is
 * actually re-usable.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @param <T> type of item to publish
 */
public class PushPullPublisher<T>
        extends AugmentedAbstractUnicastPublisher<T, SerialTransferService<T>>
{
    private final Supplier<? extends T> generator;
    private final Consumer<? super T> onNonSuccessfulDelivery;
    private final Runnable onCancel;
    
    /**
     * Initializes a reusable publisher.
     * 
     * @apiNote
     * This constructor does not accept an "on cancel" callback. Because for a
     * reusable publisher (rather, generator), a subscriber cancelling is not
     * the end of life for that publisher, more subscribers in the future are
     * expected.
     * 
     * @param generator
     *           item producer
     * @param onNonSuccessfulDelivery
     *           an opportunity to recycle items (see {@link PushPullPublisher})
     * @throws NullPointerException
     *           if any arg is {@code null}
     */
    public PushPullPublisher(
            Supplier<? extends T> generator,
            Consumer<? super T> onNonSuccessfulDelivery)
    {
        super(true);
        this.generator = requireNonNull(generator);
        this.onNonSuccessfulDelivery = requireNonNull(onNonSuccessfulDelivery);
        this.onCancel  = () -> {};
    }
    
    /**
     * Initializes a non-reusable publisher.<p>
     * 
     * As guaranteed by the {@linkplain
     * AbstractUnicastPublisher#newSubscription(Flow.Subscriber) superclass},
     * the {@code onCancel} callback:
     * <ol>
     *   <li>Is never called whilst a subscriber is initializing. In fact, this
     *       will cause the installation to roll back.</li>
     *   <li>Only executes exactly once if the subscriber's cancel-signal was
     *       the one that terminated the subscription.</li>
     * </ol>
     * 
     * In addition, the subscriber-unique
     * {@linkplain SerialTransferService#finish(Runnable) transfer service}
     * guarantees that the callback will only execute when a transfer is not
     * ongoing.<p>
     * 
     * The callback will have memory visibility of writes from the last transfer
     * as well as writes done by the subscriber's thread calling cancel.<p>
     * 
     * Both callbacks in combination is how the non-reusable publisher/generator
     * may perform resource cleanup.
     * 
     * @param generator
     *           item supplier
     * @param onNonSuccessfulDelivery
     *           an opportunity to recycle items (see {@link PushPullPublisher})
     * @param onCancel
     *           see JavaDoc
     * @throws NullPointerException
     *           if any arg is {@code null}
     */
    public PushPullPublisher(
            Supplier<? extends T> generator,
            Consumer<? super T> onNonSuccessfulDelivery,
            Runnable onCancel)
    {
        super(false);
        this.generator = requireNonNull(generator);
        this.onCancel  = requireNonNull(onCancel);
        this.onNonSuccessfulDelivery = requireNonNull(onNonSuccessfulDelivery);
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
     * Is NOP if no subscriber is active or an active subscriber's demand is
     * zero.
     */
    public void announce() {
        ifPresent(s -> s.attachment().tryTransfer());
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
     * @return {@code true} only if a subscriber received the error
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
     * @return {@code true} only if a subscriber received the error
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
     * @return {@code true} only if a subscriber received the error
     */
    public boolean stop(Throwable t) {
        var s = shutdown();
        if (s == null) {
            return false;
        }
        return s.attachment().finish(() ->
                Subscribers.signalErrorSafe(s, t));
    }
    
    private void ifPresent(
            Consumer<SubscriberWithAttachment<T, SerialTransferService<T>>> action)
    {
        var s = get();
        if (s == null) {
            return;
        }
        action.accept(s);
    }
    
    private boolean errorThroughService(
            Throwable t, SubscriberWithAttachment<T, SerialTransferService<T>> swa)
    {
        return swa.attachment().finish(() -> {
            // Attempt to terminate subscription
            if (!signalError(t, swa)) {
                // stale subscription, still need to communicate error to our guy
                Subscribers.signalErrorSafe(swa, t);
            }
        });
    }
    
    @Override
    protected SubscriberWithAttachment<T, SerialTransferService<T>>
            giveAttachment(Flow.Subscriber<? super T> sub)
    {
        SubscriberWithAttachment<T, SerialTransferService<T>> swa
                = new SubscriberWithAttachment<>(sub);
        
        swa.attachment(new SerialTransferService<>(
                generator,
                item -> {
                    if (!signalNext(item, swa)) {
                        onNonSuccessfulDelivery.accept(item);
                    }
                },
                failedItem -> {
                    shutdown();
                    onNonSuccessfulDelivery.accept(failedItem);
                }));
        
        return swa;
    }
    
    @Override
    protected Flow.Subscription newSubscription(
            SubscriberWithAttachment<T, SerialTransferService<T>> swa)
    {
        return new DelegateToService(swa);
    }
    
    private final class DelegateToService implements Flow.Subscription
    {
        private final SubscriberWithAttachment<T, SerialTransferService<T>> swa;
        
        DelegateToService(SubscriberWithAttachment<T, SerialTransferService<T>> swa) {
            this.swa = swa;
        }
        
        @Override
        public void request(long n) {
            try {
                swa.attachment().increaseDemand(n);
            } catch (IllegalArgumentException e) {
                errorThroughService(e, swa);
            }
        }
        
        @Override
        public void cancel() {
            swa.attachment().finish(onCancel);
        }
    }
}