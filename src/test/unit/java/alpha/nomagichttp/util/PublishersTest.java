package alpha.nomagichttp.util;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.concurrent.Flow;

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
        CollectingSubscriber<String> s = new CollectingSubscriber<>();
        p.subscribe(s);
        assertThat(s.items()).containsExactly("hello");
        p.subscribe(s);
        assertThat(s.items()).containsExactly("hello", "hello");
    }
    
    @Test
    void just() {
        Collection<String> items = drain(Publishers.just("one", "two"));
        assertThat(items).containsExactly("one", "two");
    }
}