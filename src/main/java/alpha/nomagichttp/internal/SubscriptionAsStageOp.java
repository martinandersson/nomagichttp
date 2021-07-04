package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.RequestBodyTimeoutException;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Optional.ofNullable;

/**
 * Allows the flow to be observed {@link #asCompletionStage()}.<p>
 * 
 * Is used by the server's request thread as a notification mechanism for
 * upstream errors (specifically, {@link RequestBodyTimeoutException}) and to be
 * notified when the application's body processing completes at which point the
 * next HTTP exchange pair may commence.<p>
 * 
 * Unlike the default operator behavior, this operator subscribes eagerly to the
 * upstream for the purpose of catching all errors. The upstream error is caught
 * even if at that time no downstream subscriber has arrived.<p>
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
        trySubscribeToUpstream();
    }
    
    /**
     * Returns a stage that completes only when the subscription completes
     * (through upstream completion/error or downstream cancellation) <i>and</i>
     * all published bytebuffers have been released.<p>
     * 
     * If the upstream signals an error, then this exception instance will be
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
     * arrives. The cure for C is for the server to have a point in time when he
     * gives up waiting (documented in {@code Request.Body} to be the point
     * when the final response-body subscription completes. Additionally, an
     * upstream operator will end the subscription on {@link
     * RequestBodyTimeoutException}.<p>
     * 
     * The terminating signal is passed through first, then the stage completes.
     * The stage will complete in a finally-block, meaning that even if {@code
     * onError}/{@code onComplete} or {@code cancel} returns exceptionally, the
     * returned stage will still complete (the event is never lost).
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
        alwaysTryComplete(
                () -> super.fromUpstreamError(t),
                TerminationCause.ofError(t));
    }
    
    @Override
    protected void fromUpstreamComplete() {
        alwaysTryComplete(
                super::fromUpstreamComplete,
                TerminationCause.ofCompletion());
    }
    
    @Override
    protected void fromDownstreamCancel() {
        alwaysTryComplete(
                super::fromDownstreamCancel,
                TerminationCause.ofCancellation());
    }
    
    private void alwaysTryComplete(Runnable signal, TerminationCause cause) {
        boolean success = terminated.compareAndSet(null, cause);
        try {
            signal.run();
        } finally {
            if (success) {
                tryCompleteStage();
            }
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