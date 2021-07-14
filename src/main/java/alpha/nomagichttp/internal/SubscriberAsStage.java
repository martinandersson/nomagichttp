package alpha.nomagichttp.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static java.util.Objects.requireNonNull;

/**
 * A single-use subscriber that processes items into an end-result retrievable
 * using {@link #asCompletionStage()}.<p>
 * 
 * If the subscriber is used more than once, it will throw an {@code
 * IllegalStateException} on the thread calling {@code onSubscribe()}. There's
 * unfortunately no other choice (
 * <a href="https://github.com/reactive-streams/reactive-streams-jvm/issues/495">
 * GitHub issue</a>).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @param <T> item type of subscriber
 * @param <R> result type of stage
 */
interface SubscriberAsStage<T, R> extends Flow.Subscriber<T>
{
    /**
     * The returned stage supports being cast to {@link
     * CompletableFuture}.<p>
     * 
     * Converting the returned stage to a {@code CompletableFuture} and then
     * completing the future does not necessarily translate to a cancellation of
     * the underlying subscription.
     * 
     * @return this as a completion stage
     */
    CompletionStage<R> asCompletionStage();
    
    /**
     * Returns {@code argument} if {@code field} is {@code null}.<p>
     * 
     * This is a utility method meant to be used by the subscriber's {@code
     * onSubscribe()} implementation.
     * 
     * @param field subscription instance field of subscriber
     * @param argument subscription instance passed to {@code onSubscribe()}
     * 
     * @return {@code argument}
     * 
     * @throws NullPointerException   if {@code argument} is {@code null}
     * @throws IllegalStateException  if {@code field} is <strong>not</strong> {@code null}
     */
    static Flow.Subscription validate(Flow.Subscription field, Flow.Subscription argument) {
        requireNonNull(argument);
        if (field != null) {
            argument.cancel();
            throw new IllegalStateException("No support for subscriber re-use.");
        }
        return argument;
    }
}