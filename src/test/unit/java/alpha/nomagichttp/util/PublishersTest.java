package alpha.nomagichttp.util;

import alpha.nomagichttp.testutil.MemorizingSubscriber;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.Request;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainItems;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainMethods;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests for {@link Publishers}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class PublishersTest
{
    @Test
    void just_reuse() {
        Flow.Publisher<String> p = Publishers.just("one");
        MemorizingSubscriber<String> s = new MemorizingSubscriber<>(Request.IMMEDIATELY_MAX());
        p.subscribe(s);
        assertThat(s.items()).containsExactly("one");
        p.subscribe(s);
        assertThat(s.items()).containsExactly("one", "one");
    }
    
    @Test
    void just_two_items() {
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
    void just_empty_with_cancellation() {
        MemorizingSubscriber<Object> s = new MemorizingSubscriber<>(Request.NOTHING()){
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.cancel();
                super.onSubscribe(subscription);
            }
        };
        
        Publishers.just().subscribe(s);
        
        assertThat(s.signals().map(Signal::getMethodName)).containsExactly(
                ON_SUBSCRIBE);
               // but not ON_COMPLETE, because we cancelled!
    }
}