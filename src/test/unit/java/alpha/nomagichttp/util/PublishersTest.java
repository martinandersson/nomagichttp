package alpha.nomagichttp.util;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.util.MemorizingSubscriber.Request;
import static alpha.nomagichttp.util.MemorizingSubscriber.Signal;
import static alpha.nomagichttp.util.MemorizingSubscriber.drain;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests for {@link Publishers}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class PublishersTest
{
    @Test
    void just_one_repeat() {
        Flow.Publisher<String> p = Publishers.just("one");
        MemorizingSubscriber<String> s = new MemorizingSubscriber<>(Request.IMMEDIATELY_MAX());
        p.subscribe(s);
        assertThat(s.items()).containsExactly("one");
        p.subscribe(s);
        assertThat(s.items()).containsExactly("one", "one");
    }
    
    @Test
    void just_one_two() {
        Collection<String> items = drain(Publishers.just("one", "two"));
        assertThat(items).containsExactly("one", "two");
    }
    
    @Test
    void just_nothing() {
        MemorizingSubscriber<Object> s = new MemorizingSubscriber<>(Request.NOTHING());
        
        Publishers.just().subscribe(s);
        
        assertThat(s.signals()).containsExactly(
                Signal.Subscribe.class,
                Signal.Complete.class);
    }
    
    @Test
    void just_nothing_with_cancellation() {
        MemorizingSubscriber<Object> s = new MemorizingSubscriber<>(Request.NOTHING()){
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