package alpha.nomagichttp.util;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static alpha.nomagichttp.util.DummyScopedValue.newInstance;
import static alpha.nomagichttp.util.DummyScopedValue.where;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests for {@link DummyScopedValue}.
 */
final class DummyScopedValueTest
{
    final DummyScopedValue<String> testee = newInstance();
    
    @Test
    void bind() {
        assertNotBound();
        where(testee, "Hello", () -> assertIsBound("Hello"));
        assertNotBound();
    }
    
    @Test
    void rebind() {
        where(testee, "Hello", () -> {
            assertIsBound("Hello");
            where(testee, "World", () -> {
                assertIsBound("World");
            });
            assertIsBound("Hello");
        });
        assertNotBound();
    }
    
    private void assertNotBound() {
        assertThat(testee.isBound()).isFalse();
        assertThatThrownBy(testee::get)
                .isExactlyInstanceOf(NoSuchElementException.class);
    }
    
    private void assertIsBound(String expected) {
        assertThat(testee.get()).isEqualTo(expected);
    }
}