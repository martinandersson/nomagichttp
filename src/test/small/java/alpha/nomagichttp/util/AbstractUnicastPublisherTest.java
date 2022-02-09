package alpha.nomagichttp.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Flow;

import static alpha.nomagichttp.testutil.Assertions.assertPublisherError;
import static alpha.nomagichttp.testutil.Assertions.assertPublisherIsEmpty;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainMethods;
import static alpha.nomagichttp.testutil.TestSubscribers.onSubscribe;
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
        var testee = new ImmediatelyCompleteImpl(false);
        assertPublisherIsEmpty(testee);
        assertNoReuse(testee);
    }
    
    @Test
    void pubImmediatelyCompletes_reusable() {
        var testee = new ImmediatelyCompleteImpl(true);
        assertPublisherIsEmpty(testee);
        assertPublisherIsEmpty(testee);
    }
    
    @Test
    void subImmediatelyCancels_noReuse() {
        var testee = new AcceptNoSubscriberImpl();
        cancelAndAssertInit(testee);
        assertNoReuse(testee);
    }
    
    @Test
    void subImmediatelyCancels_reusable() {
        var testee = new NoopImpl(true);
        cancelAndAssertInit(testee);
        assertThat(drainMethods(testee))
            .containsExactly(ON_SUBSCRIBE);
    }
    
    @Test
    void tryShutdown_noSubscriber() {
        var testee = new NoopImpl(false);
        assertThat(testee.tryShutdown()).isTrue();
        assertPublisherError(testee)
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("Publisher has shutdown.")
            .hasNoCause()
            .hasNoSuppressedExceptions();
    }
    
    @Test
    void tryShutdown_withSubscriber() {
        var testee = new NoopImpl(false);
        testee.subscribe(Subscribers.noop());
        assertThat(testee.tryShutdown()).isFalse();
        assertPublisherError(testee)
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("Publisher already has a subscriber.")
            .hasNoCause()
            .hasNoSuppressedExceptions();
    }
    
    private static void assertNoReuse(Flow.Publisher<?> pub) {
        assertPublisherError(pub)
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
    
    static class ImmediatelyCompleteImpl extends AbstractUnicastPublisher<Void> {
        ImmediatelyCompleteImpl(boolean reusable) {
            super(reusable); }
        protected Flow.Subscription newSubscription(Flow.Subscriber<? super Void> s) {
            assertThat(signalComplete()).isTrue();
            return Subscriptions.noop(); }
    }
    
    static class AcceptNoSubscriberImpl extends AbstractUnicastPublisher<Void> {
        AcceptNoSubscriberImpl() {
            super(false); }
        protected Flow.Subscription newSubscription(Flow.Subscriber<? super Void> s) {
            throw new AssertionError(); }
    }
    
    static class NoopImpl extends AbstractUnicastPublisher<Void> {
        NoopImpl(boolean reusable) {
            super(reusable); }
        protected Flow.Subscription newSubscription(Flow.Subscriber<? super Void> s) {
            return Subscriptions.noop(); }
    }
}