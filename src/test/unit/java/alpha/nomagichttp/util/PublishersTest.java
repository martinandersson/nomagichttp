package alpha.nomagichttp.util;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.util.CollectingSubscriber.Request;
import static alpha.nomagichttp.util.CollectingSubscriber.Signal;
import static alpha.nomagichttp.util.CollectingSubscriber.drain;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests for {@link Publishers}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class PublishersTest
{
    @Test
    void singleton() {
        Flow.Publisher<String> p = Publishers.singleton("hello");
        CollectingSubscriber<String> s = new CollectingSubscriber<>(Request.IMMEDIATELY_MAX());
        p.subscribe(s);
        assertThat(s.items()).containsExactly("hello");
        p.subscribe(s);
        assertThat(s.items()).containsExactly("hello", "hello");
    }
    
    @Test
    void just_one_two() {
        Collection<String> items = drain(Publishers.just("one", "two"));
        assertThat(items).containsExactly("one", "two");
    }
    
    @Test
    void just_nothing() {
        CollectingSubscriber<Object> s = new CollectingSubscriber<>(Request.NOTHING());
        
        Publishers.just().subscribe(s);
        
        assertThat(s.signals()).containsExactly(
                Signal.Subscribe.class,
                Signal.Complete.class);
    }
    
    @Test
    void just_nothing_with_cancellation() {
        CollectingSubscriber<Object> s = new CollectingSubscriber<>(Request.NOTHING()){
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.cancel();
                super.onSubscribe(subscription);
            }
        };
        
        Publishers.just().subscribe(s);
        
        assertThat(s.signals()).containsExactly(
                Signal.Subscribe.class); // but no complete, because we cancelled!
    }
}