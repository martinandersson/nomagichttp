package alpha.nomagichttp.testutil;

import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.ObjectAssert;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_ERROR;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.TestSubscribers.requestMax;
import static java.time.Duration.ZERO;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assertion utils.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Assertions {
    private Assertions() {
        // Empty
    }
    
    /**
     * Extracts the succeeded result from the given stage.
     * 
     * @param <T> result type
     * @param stage to extract result from
     * @return the result
     */
    public static <T> ObjectAssert<T> assertSucceeded(
            CompletionStage<T> stage) {
        return assertThat(stage).succeedsWithin(ZERO);
    }
    
    /**
     * Extracts the failed cause of the given stage.<p>
     * 
     * Fails immediately if the stage is not completed.
     * 
     * @param stage testee
     * @return a throwable assert
     * @throws NullPointerException if {@code stage} is {@code null}
     */
    public static AbstractThrowableAssert<?, ? extends Throwable>
            assertFailed(CompletionStage<?> stage)
    {
        CompletableFuture<?> f = stage.toCompletableFuture();
        assertThat(f).isCompletedExceptionally();
        try {
            f.getNow(null);
        } catch (CompletionException e) {
            return assertThat(e).getCause();
        }
        throw new AssertionError();
    }
    
    /**
     * Extracts the failed cause of the given stage.<p>
     * 
     * Waits at most 3 seconds for the completion of the stage.
     * 
     * @param stage testee
     * @return a throwable assert
     * @throws NullPointerException if {@code stage} is {@code null}
     * @throws InterruptedException if interrupted
     * @throws TimeoutException if 3 seconds pass
     */
    public static AbstractThrowableAssert<?, ? extends Throwable>
            assertFails(CompletionStage<?> stage)
            throws InterruptedException, TimeoutException
    {
        try {
            stage.toCompletableFuture().get(3, SECONDS);
        } catch (ExecutionException e) {
            return assertThat(e).getCause();
        }
        throw new AssertionError("Did not complete exceptionally.");
    }
    
    /**
     * Assert that the given stage was cancelled.
     * 
     * @param stage to verify
     */
    public static void assertCancelled(CompletionStage<?> stage) {
        // This is essentially what AssertJ do:
        if (stage.toCompletableFuture().isCancelled()) {
            // Okay, great
            return;
        }
        // Except a copy or a minimal stage will not answer truthfully lol, need to probe the cause
        assertFailed(stage).isExactlyInstanceOf(CancellationException.class);
    }
    
    /**
     * Drain all signals from the publisher and assert that the publisher
     * published only one error signal.
     * 
     * @param publisher to drain
     * @return throwable assert
     * @see MemorizingSubscriber#drainSignals(Flow.Publisher) 
     */
    public static AbstractThrowableAssert<?, ? extends Throwable>
            assertPublisherError(Flow.Publisher<?> publisher) {
        var s = requestMax();
        publisher.subscribe(s);
        return assertSubscriberOnError(s);
    }
    
    /**
     * Assert that the subscriber received exactly one signalled error.
     * 
     * @param subscriber to assert
     * @return throwable assert
     */
    public static AbstractThrowableAssert<?, ? extends Throwable>
            assertSubscriberOnError(MemorizingSubscriber<?> subscriber)
    {
        var received = subscriber.signals();
        assertThat(received).hasSize(2);
        assertThat(received.get(0).methodName()).isEqualTo(ON_SUBSCRIBE);
        assertThat(received.get(1).methodName()).isEqualTo(ON_ERROR);
        return assertThat(received.get(1).<Throwable>argumentAs());
    }
}