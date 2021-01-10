package alpha.nomagichttp.internal;

import org.junit.jupiter.api.Test;

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
    void first_key_segment_is_always_the_empty_string_1() {
        // Pass no key segments at all
        assertThatThrownBy(() -> testee.setIfAbsent(of(), null, null))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("First key segment must be the empty String (root).");
    }
    
    @Test
    void first_key_segment_is_always_the_empty_string_2() {
        // Let first key segment not be the empty string
        assertThatThrownBy(() -> testee.setIfAbsent(of("crash"), null, null))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("First key segment must be the empty String (root).");
    }
    
    @Test
    void set_root() {
        testee.setIfAbsent(of(""), "value");
        
        assertThat(testee.toMap("/")).containsExactly(
                entry("/", "value"));
    }
    
    @Test
    void level_one() {
        testee.setIfAbsent(of("", "blabla"), "value");
        
        assertThat(testee.toMap("/")).containsExactly(
                entry("/", null),
                entry("/blabla", "value"));
    }
    
    @Test
    void lots_of_them() {
        testee.setIfAbsent(of("", "a", "b", "c"), "v3");
        testee.setIfAbsent(of("", "a", "b"), "v2");
        testee.setIfAbsent(of("", "a"), "v1");
        testee.setIfAbsent(of("", "a", "d"), "v4");
        
        assertThat(testee.toMap("/")).containsExactly(
                entry("/", null),
                entry("/a", "v1"),
                entry("/a/b", "v2"),
                entry("/a/b/c", "v3"),
                entry("/a/d", "v4"));
    }
    
    @Test
    void pruning() {
        testee.setIfAbsent(of("", "a", "b"), "v1");
        
        assertThat(testee.toMap("/")).containsExactly(
                entry("/", null),
                entry("/a", null),
                entry("/a/b", "v1"));
        
        testee.clear(of("", "trigger pruning"));
        
        // No difference, last node had a value
        assertThat(testee.toMap("/")).containsExactly(
                entry("/", null),
                entry("/a", null),
                entry("/a/b", "v1"));
        
        testee.clear(of("", "a", "b"));
        
        // This time the entire branch is wiped clean
        assertThat(testee.toMap("/")).containsExactly(
                entry("/", null));
    }
}