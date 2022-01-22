package alpha.nomagichttp.util;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainItems;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainMethods;
import static alpha.nomagichttp.testutil.TestSubscribers.onSubscribe;
import static alpha.nomagichttp.testutil.TestSubscribers.requestMax;
import static alpha.nomagichttp.util.Publishers.just;
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
        var publisher = just("one");
        var subscriber = requestMax();
        publisher.subscribe(subscriber);
        assertThat(subscriber.items()).containsExactly("one");
        publisher.subscribe(subscriber);
        assertThat(subscriber.items()).containsExactly("one", "one");
    }
    
    @Test
    void just_withTwoItems() {
        Collection<String> items = drainItems(just("one", "two"));
        assertThat(items).containsExactly("one", "two");
    }
    
    @Test
    void just_empty() {
        assertThat(drainMethods(just())).containsExactly(
                ON_SUBSCRIBE,
                ON_COMPLETE);
    }
    
    @Test
    void just_empty_cancelImmediately() {
        var s = onSubscribe(Flow.Subscription::cancel);
        just().subscribe(s);
        // but not ON_COMPLETE, because we cancelled!
        assertThat(s.methodNames()).containsExactly(ON_SUBSCRIBE);
    }
}