package alpha.nomagichttp.util;

import alpha.nomagichttp.testutil.MemorizingSubscriber;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Flow;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_ERROR;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainMethods;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainSignals;
import static alpha.nomagichttp.testutil.TestSubscribers.onSubscribe;
import static alpha.nomagichttp.util.Subscriptions.noop;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests of {@link AbstractUnicastPublisher}.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class AbstractUnicastPublisherTest
{
    @Test
    void pubImmediatelyCompletes_noReuse() {
        var testee = new ImmediatelyComplete(false);
        assertThat(drainMethods(testee)).containsExactly(
                ON_SUBSCRIBE,
                ON_COMPLETE);
        assertNoReuse(testee);
    }
    
    @Test
    void pubImmediatelyCompletes_reusable() {
        var testee = new ImmediatelyComplete(true);
        assertThat(drainMethods(testee)).containsExactly(
                ON_SUBSCRIBE,
                ON_COMPLETE);
        assertThat(drainMethods(testee)).containsExactly(
                ON_SUBSCRIBE,
                ON_COMPLETE);
    }
    
    @Test
    void subImmediatelyCancels_noReuse() {
        var testee = new AcceptNoSubscriber();
        cancelAndAssertInit(testee);
        assertNoReuse(testee);
    }
    
    @Test
    void subImmediatelyCancels_reusable() {
        var testee = new Noop(true);
        cancelAndAssertInit(testee);
        assertThat(drainMethods(testee))
                .containsExactly(ON_SUBSCRIBE);
    }
    
    private static void assertNoReuse(Flow.Publisher<?> pub) {
        var s = drainSignals(pub);
        assertThat(s.size()).isEqualTo(2);
        assertThat(s.get(0).methodName()).isEqualTo(ON_SUBSCRIBE);
        assertThat(s.get(1).methodName()).isEqualTo(ON_ERROR);
        assertThat(s.get(1).<Exception>argumentAs())
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Publisher was already subscribed to and is not reusable.")
                .hasNoCause()
                .hasNoSuppressedExceptions();
    }
    
    private static void cancelAndAssertInit(Flow.Publisher<?> pub) {
        // Immediately cancel during initialization (a "roll back")
        var s = onSubscribe(Flow.Subscription::cancel);
        pub.subscribe(s);
        assertThat(s.methodNames()).containsExactly(ON_SUBSCRIBE);
    }
    
    private static class ImmediatelyComplete
            extends AbstractUnicastPublisher<Void>
    {
        ImmediatelyComplete(boolean reusable) {
            super(reusable);
        }
        @Override protected Flow.Subscription newSubscription(
                Flow.Subscriber<? super Void> s) {
            assertThat(signalComplete()).isTrue();
            return noop();
        }
    }
    
    private static class AcceptNoSubscriber extends AbstractUnicastPublisher<Void> {
        AcceptNoSubscriber() {
            super(false);
        }
        @Override protected Flow.Subscription newSubscription(
                Flow.Subscriber<? super Void> subscriber) {
            throw new AssertionError();
        }
    }
    
    private static class Noop extends AbstractUnicastPublisher<Void> {
        Noop(boolean reusable) {
            super(reusable);
        }
        @Override protected Flow.Subscription newSubscription(
                Flow.Subscriber<? super Void> subscriber) {
            return noop();
        }
    }
}