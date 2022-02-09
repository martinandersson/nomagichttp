package alpha.nomagichttp.testutil;

import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.ObjectAssert;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_ERROR;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_NEXT;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainSignals;
import static alpha.nomagichttp.testutil.TestSubscribers.requestMax;
import static alpha.nomagichttp.util.Streams.stream;
import static java.time.Duration.ZERO;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toCollection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;

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
     * completes normally without publishing items.
     * 
     * @param publisher to drain
     * @see MemorizingSubscriber#drainSignals(Flow.Publisher) 
     */
    public static void assertPublisherIsEmpty(Flow.Publisher<?> publisher) {
        var signals = drainSignals(publisher);
        assertThat(signals).hasSize(2);
        assertSame(signals.get(0).methodName(), ON_SUBSCRIBE);
        assertSame(signals.get(1).methodName(), ON_COMPLETE);
    }
    
    /**
     * Drain all signals from the publisher and assert that the publisher
     * emits only the given items.
     * 
     * @param <T> published item type
     * @param publisher to drain
     * @param first item
     * @param more items
     * @see MemorizingSubscriber#drainSignals(Flow.Publisher) 
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> void assertPublisherEmits(
            Flow.Publisher<? extends T> publisher, T first, T... more) {
        assertItems(drainSignals(publisher), first, more);
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
     * Assert that the subscriber received exactly the given items.
     * 
     * @param <T> subscribed type
     * @param subscriber to assert
     * @param first item
     * @param more items
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> void assertSubscriberOnNext(
            MemorizingSubscriber<? extends T> subscriber, T first, T... more) {
        assertItems(subscriber.signals(), first, more);
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
    
    @SafeVarargs
    private static <T> void assertItems(
            List<Signal> actual, T first, T... more)
    {
        @SuppressWarnings("varargs")
        var expectedItems = stream(first, more)
            .peek(i -> assertThat(i).isNotNull())
            .collect(toCollection(ArrayList::new));
        
        // Items + ON_SUBSCRIBE and ON_COMPLETE
        assertThat(actual).hasSize(expectedItems.size() + 2);
        
        // Fist signal
        assertThat(actual.get(0)
            .methodName()).isEqualTo(ON_SUBSCRIBE);
        
        // Assert the published items
        var itemSignals = actual.subList(1, actual.size() - 1);
        for (int i = 0; i < itemSignals.size(); ++i) {
            var act = itemSignals.get(i);
            assertThat(act.methodName()).isEqualTo(ON_NEXT);
            assertThat(act.argument()).isEqualTo(expectedItems.get(i));
        }
        
        // Last signal
        assertThat(actual.get(actual.size() - 1)
            .methodName()).isEqualTo(ON_COMPLETE);
    }
}