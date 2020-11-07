package alpha.nomagichttp.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * A single-use subscriber that supports being viewed
 * {@link #asCompletionStage()} of the end result.<p>
 * 
 * If the subscriber is used more than once, it will throw an {@code
 * IllegalStateException} on the thread calling {@code onSubscribe()}. There's
 * unfortunately no other choice (see <a
 * href="https://github.com/reactive-streams/reactive-streams-jvm/issues/495">
 * GitHub issue</a>).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
interface SubscriberAsStage<T, R> extends Flow.Subscriber<T>
{
    /**
     * The returned stage supports being cast to {@link
     * CompletableFuture<R>}.<p>
     * 
     * Converting the returned stage to a {@code CompletableFuture} and then
     * completing the future does not necessarily translate to a cancellation of
     * the underlying subscription used in this class.
     * 
     * @return this as a completion stage
     */
    CompletionStage<R> asCompletionStage();
}
