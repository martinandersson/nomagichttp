package alpha.nomagichttp.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Flow;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toCollection;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of {@code AbstractUnicastPublisher} using a local concrete
 * implementation.
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
    
    private static final class StringPublisher extends AbstractUnicastPublisher<String>
    {
        private final Queue<String> items;
        
        StringPublisher(String... items) {
            this.items = stream(items).collect(toCollection(ArrayDeque::new));
        }
        
        @Override
        protected String poll() {
            return items.poll();
        }
    }
    
    private static final class StringSubscriber implements Flow.Subscriber<String>
    {
        private final List<String> items;
        private final int demand;
        private Flow.Subscription sub;
        
        StringSubscriber(int demand) {
            this.items = new ArrayList<>();
            this.demand = demand;
        }
        
        List<String> items() {
            return items;
        }
        
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            sub = subscription;
            sub.request(demand);
        }
        
        @Override
        public void onNext(String item) {
            if (items.size() == demand) {
                throw new AssertionError(
                        "Subscription was cancelled [synchronously by same thread, I assume].");
            }
            
            items.add(item);
            
            if (items.size() == demand) {
                sub.cancel();
            }
        }
        
        @Override
        public void onError(Throwable throwable) {
            throw new AssertionError(throwable);
        }
        
        @Override
        public void onComplete() {
            // Empty
        }
    }
}