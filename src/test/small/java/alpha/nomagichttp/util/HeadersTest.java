package alpha.nomagichttp.util;

import org.assertj.core.api.MapAssert;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests of {@link Headers}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class HeadersTest
{
    @Test
    void of_empty() {
        assertPairs().isEmpty();
    }
    
    @Test
    void of_odd_1() {
        assertThatThrownBy(() -> Headers.of("not a pair"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }
    
    @Test
    void of_odd_2() {
        assertThatThrownBy(() -> Headers.of("a", "pair", "but one extra"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }
    
    @Test
    void of_pair_1() {
        assertPairs("name", "val").containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("name", List.of("val"))));
    }
    
    @Test
    void of_pair_2() {
        assertPairs("k1", "v1", "k2", "v2").containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("k1", List.of("v1")),
                Map.entry("k2", List.of("v2"))));
    }
    
    @Test
    void of_multiValued() {
        assertPairs("k1", "v1", "k2", "v2", "k1", "v3").containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("k1", List.of("v1", "v3")),
                Map.entry("k2", List.of("v2"))));
    }
    
    private static MapAssert<String, List<String>> assertPairs(String... nameValuePairs) {
        return assertThat(Headers.of(nameValuePairs));
    }
}