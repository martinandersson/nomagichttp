package alpha.nomagichttp.util;

import alpha.nomagichttp.testutil.MemorizingSubscriber;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainItems;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainMethods;
import static alpha.nomagichttp.testutil.TestSubscribers.onSubscribe;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests for {@link Publishers}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class PublishersTest
{
    @Test
    void just_isReusable() {
        Flow.Publisher<String> p = Publishers.just("one");
        MemorizingSubscriber<String> s = new MemorizingSubscriber<>();
        p.subscribe(s);
        assertThat(s.items()).containsExactly("one");
        p.subscribe(s);
        assertThat(s.items()).containsExactly("one", "one");
    }
    
    @Test
    void just_withTwoItems() {
        Collection<String> items = drainItems(Publishers.just("one", "two"));
        assertThat(items).containsExactly("one", "two");
    }
    
    @Test
    void just_empty() {
        assertThat(drainMethods(Publishers.just())).containsExactly(
                ON_SUBSCRIBE,
                ON_COMPLETE);
    }
    
    @Test
    void just_empty_cancelImmediately() {
        var s = new MemorizingSubscriber<>(onSubscribe(Flow.Subscription::cancel));
        Publishers.just().subscribe(s);
        // but not ON_COMPLETE, because we cancelled!
        assertThat(s.methodNames()).containsExactly(ON_SUBSCRIBE);
    }
}