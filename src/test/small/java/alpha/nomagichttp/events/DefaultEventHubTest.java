package alpha.nomagichttp.events;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small tests of {@code DefaultEventHub}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class DefaultEventHubTest {
    private final Set<String> events = new HashSet<>();
    private final BiConsumer<String, ?> collector
            = (ev, att) -> events.add(ev + ":" + att);
    
    @Test
    void oneListenerCycle() {
        EventHub eh = new DefaultEventHub();
        assertTrue(eh.on(String.class, collector));
        assertThat(eh.dispatch("Hello", 123, "ignored")).isOne();
        assertThat(events).containsExactly("Hello:123");
        events.clear();
        assertTrue(eh.off(String.class, collector));
        assertThat(eh.dispatch("no recipient")).isZero();
        assertThat(events).isEmpty();
    }
    
    @Test
    void bridged() {
        EventHub first  = new DefaultEventHub(),
                 second = new DefaultEventHub();
        second.redistribute(first);
        second.on(String.class, collector);
        first.dispatch("Hello");
        assertThat(events).containsExactly("Hello:null");
    }
    
    @Test
    void eventHub_javadoc_redistribute_bad() {
        AtomicInteger n = new AtomicInteger();
        EventHub first = new DefaultEventHub(),
                 second = new DefaultEventHub();
        first.on(Integer.class, (ev) -> n.incrementAndGet());
        second.on(Integer.class, (ev) -> n.incrementAndGet());
        second.redistribute(first);
        EventHub global = second;
        global.on(Integer.class, (ev) -> n.incrementAndGet());
        global.dispatch(1);
        assertThat(n).hasValue(2);
    }
    
    @Test
    void eventHub_javadoc_redistribute_fix() {
        AtomicInteger n = new AtomicInteger();
        EventHub first = new DefaultEventHub(),
                second = new DefaultEventHub();
        first.on(Integer.class, (ev) -> n.incrementAndGet());
        second.on(Integer.class, (ev) -> n.incrementAndGet());
        EventHub global = EventHub.combine(first, second);
        global.on(Integer.class, (ev) -> n.incrementAndGet());
        global.dispatch(1);
        assertThat(n).hasValue(1);
    }
}