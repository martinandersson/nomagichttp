package alpha.nomagichttp.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests for {@link Confined}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ConfinedTest
{
    private final Confined<String> testee = new Confined<>();
    
    @Test
    void api() {
        assertEmpty();
        var str = testee.init(() -> "whatevs");
        assertThat(str).isEqualTo("whatevs");
        assertContains("whatevs");
        var success = testee.drop(v ->
                assertThat(v).isEqualTo("whatevs"));
        assertThat(success).isTrue();
        assertEmpty();
    }
    
    private void assertEmpty() {
        assertThat(testee.isPresent()).isFalse();
        assertThat(testee.peek()).isEmpty();
        assertThat(testee.drop(x -> {
            throw new AssertionError();
        })).isFalse();
    }
    
    private void assertContains(String expected) {
        assertThat(testee.isPresent()).isTrue();
        assertThat(testee.peek()).contains(expected);
        assertThat(testee.init(() -> {
            throw new AssertionError();
        })).isNull();
    }
}