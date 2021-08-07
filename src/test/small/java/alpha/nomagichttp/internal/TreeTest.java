package alpha.nomagichttp.internal;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;

import static alpha.nomagichttp.internal.Tree.entry;
import static java.util.List.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests for {@link Tree}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class TreeTest
{
    private final Tree<String> testee = new Tree<>();
    
    @Test
    void empty_segment() {
        assertThatThrownBy(() -> testee.setIfAbsent(of(""), "blabla"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Segment value is empty.");
    }
    
    @Test
    void set_root() {
        testee.setIfAbsent(of(), "value");
        assertThat(testee.toMap("/")).containsExactly(
                entry("/", "value"));
    }
    
    @Test
    void level_one() {
        testee.setIfAbsent(of("blabla"), "value");
        assertThat(testee.toMap("/")).containsExactly(
                entry("/", null),
                entry("/blabla", "value"));
    }
    
    @Test
    void lots_of_them() {
        testee.setIfAbsent(of("a", "b", "c"), "v3");
        testee.setIfAbsent(of("a", "b"), "v2");
        testee.setIfAbsent(of("a"), "v1");
        testee.setIfAbsent(of("a", "d"), "v4");
        
        assertThat(testee.toMap("/")).containsExactly(
                entry("/", null),
                entry("/a", "v1"),
                entry("/a/b", "v2"),
                entry("/a/b/c", "v3"),
                entry("/a/d", "v4"));
    }
    
    @Test
    void pruning() {
        testee.setIfAbsent(of("a"), "v1");
        testee.setIfAbsent(of("a", "b"), "v2");
        
        assertThat(testee.toMap("/")).containsExactly(
                entry("/", null),
                entry("/a", "v1"),
                entry("/a/b", "v2"));
        
        testee.clear(of("a"));
        
        // Branch still kept
        assertThat(testee.toMap("/")).containsExactly(
                entry("/", null),
                entry("/a", null),
                entry("/a/b", "v2"));
        
        testee.clear(of("a", "b"));
        
        // This time the entire branch was pruned/wiped clean
        assertThat(testee.toMap("/")).containsExactly(
                entry("/", null));
    }
    
    @Test
    void write_read() {
        Iterator<String> segments = List.of("a", "b").iterator();
        testee.write(n -> {
            if (segments.hasNext()) {
                return n.nextOrCreate(segments.next());
            }
            n.set("value");
            return null;
        });
        
        assertThat(testee.read().next("a").next("b").get()).isEqualTo("value");
    }
}