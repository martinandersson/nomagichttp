package alpha.nomagichttp.internal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of {@code AbstractUnicastPublisher} using a local concrete
 * {@code StringPublisher} implementation.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class AbstractUnicastPublisherTest
{
    @Test
    void two_cycles_of_subscribe_cancel() {
        AbstractUnicastPublisher<String> testee = new StringPublisher("one", "two");
        assertItems(testee, "one");
        assertItems(testee, "two");
    }
    
    private static void assertItems(Flow.Publisher<String> publisher, String... expected) {
        StringSubscriber sub = new StringSubscriber(1);
        publisher.subscribe(sub);
        assertThat(sub.items()).containsExactly(expected);
    }
}