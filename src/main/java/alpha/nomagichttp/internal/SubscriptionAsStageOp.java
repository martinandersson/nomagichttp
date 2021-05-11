package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Optional.ofNullable;

/**
 * Allows the downstream subscription to be observed {@link
 * #asCompletionStage()}.<p>
 * 
 * Is used by the server's request thread as a notification mechanism for when
 * the application's body processing completes at which point the next HTTP
 * exchange pair may commence.<p>
 * 
 * This operator doesn't change any semantics regarding the flow between the
 * upstream and downstream. All signals are passed through as-is, even
 * <i>after</i> it is noticed by this class that the subscription has ended.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class SubscriptionAsStageOp extends AbstractOp<PooledByteBufferHolder>
{
    /** A count of bytebuffers in-flight. */
    private final AtomicInteger processing;
    
    /** The upstream- or downstream's terminal event. */
    private final AtomicReference<TerminationCause> terminated;
    
    /** Stage completes when no items are in-flight and the subscription has terminated. */
    private final CompletableFuture<Void> result;
    
    /** Evaluates condition and possibly completes the stage. */
    private void tryCompleteStage() {
        final TerminationCause cause;
        if ((cause = terminated.get()) == null || processing.get() > 0) {
            return;
        }
        
        cause.error().ifPresentOrElse(result::completeExceptionally, () -> {
            if (cause.wasCancelled()) {
                boolean parameterHasNoEffect = true;
                result.cancel(parameterHasNoEffect);
            } else {
                assert cause.wasCompleted();
                result.complete(null);
            }
        });
    }
    
    SubscriptionAsStageOp(Flow.Publisher<? extends PooledByteBufferHolder> upstream) {
        super(upstream);
        processing = new AtomicInteger();
        terminated = new AtomicReference<>(null);
        result     = new CompletableFuture<>();
    }
    
    /**
     * Returns a stage that completes only when the downstream subscription
     * completes (through upstream completion/error or downstream cancellation)
     * <i>and</i> all published bytebuffers have been released.<p>
     * 
     * If the upstreams signals an error, then this exception instance will be
     * passed to the downstream subscriber and become the exception that
     * completes the stage returned from this method.<p>
     * 
     * If the downstream cancels the subscription, the returned stage completes
     * exceptionally with a {@link CancellationException}.<p>
     * 
     * There is obviously a race between signals coming in from the upstream and
     * asynchronous signals coming in from the downstream. This means there are
     * no guarantees that A) the upstream publisher immediately stops publishing
     * items after downstream cancellation - so buffers may be processed even
     * after the stage completes - and B) the stage completes in exactly the
     * same manner as agreed by both the upstream and downstream.<p>
     * 
     * In the context of the server's request thread, the cure for both A and B
     * is for the server to sandwich the stage-operator between an upstream
     * {@link LengthLimitedOp} and a downstream {@link OnCancelDiscardOp}. This
     * will ensure no trailing bytebuffers beyond the message boundary will be
     * published and it also completely takes out the cancellation signal from
     * the equation; the returned stage will only complete normally or
     * exceptionally as determined by the upstream.<p>
     * 
     * The stage may never complete if C) the application's subscriber never
     * arrives, or D) any terminating method that this class delegates to
     * ({@code Subscriber.onComplete} or {@code Subscription.cancel()}) returns
     * exceptionally.<p>
     * 
     * The cure for C is for the server to have a point in time when he gives up
     * waiting (documented in {@code Request.Body} to be the point when
     * the response-body subscription completes or on {@code
     * RequestBodyTimeoutException}). For this reason, the returned stage
     * supports being cast to a {@code CompletableFuture} (or do {@code
     * CompletionStage.toCompletableFuture()}) so that client code may complete
     * the stage or query about its state {@code CompletableFuture.isDone()}.<p>
     * 
     * D "should" never happen as the reactive streams specification mandates
     * that all methods should return normally (§2.13, §3.15). However, in the
     * event shit does hit the fan, the exception propagates as-is, eventually
     * reaching the top layer which on the server's side is a thread running
     * through the {@code ChannelByteBufferPublisher} which logs the error and
     * closes the channel's read stream. So as long as no thread is
     * <i>blocked</i> waiting on the returned stage, then a stage that never
     * completes is simply not a problem.
     * 
     * @return a stage bound to the life-cycle of the singleton subscription
     */
    CompletionStage<Void> asCompletionStage() {
        return result;
    }
    
    @Override
    protected void fromUpstreamNext(PooledByteBufferHolder item) {
        item.onRelease(readCountIgnored -> {
            if (processing.decrementAndGet() == 0) {
                tryCompleteStage();
            }
        });
        
        processing.incrementAndGet();
        super.fromUpstreamNext(item);
    }
    
    @Override
    protected void fromUpstreamError(Throwable t) {
        boolean updated = terminated.compareAndSet(null, TerminationCause.ofError(t));
        super.fromUpstreamError(t);
        if (updated) {
            tryCompleteStage();
        }
    }
    
    @Override
    protected void fromUpstreamComplete() {
        boolean updated = terminated.compareAndSet(null, TerminationCause.ofCompletion());
        super.fromUpstreamComplete();
        if (updated) {
            tryCompleteStage();
        }
    }
    
    @Override
    protected void fromDownstreamCancel() {
        boolean updated = terminated.compareAndSet(null, TerminationCause.ofCancellation());
        super.fromDownstreamCancel();
        if (updated) {
            tryCompleteStage();
        }
    }
    
    private static class TerminationCause {
        private static final TerminationCause
                CANCELLED = new TerminationCause(null),
                COMPLETED = new TerminationCause(null);
        
        private final Optional<Throwable> t;
        
        private TerminationCause(Throwable t) {
            this.t = ofNullable(t);
        }
        
        static TerminationCause ofCancellation() {
            return CANCELLED;
        }
        
        static TerminationCause ofError(Throwable t) {
            return new TerminationCause(t);
        }
        
        static TerminationCause ofCompletion() {
            return COMPLETED;
        }
        
        Optional<Throwable> error() {
            return t;
        }
        
        boolean wasCancelled() {
            return this == CANCELLED;
        }
        
        boolean wasCompleted() {
            return this == COMPLETED;
        }
    }
}